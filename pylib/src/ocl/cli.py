"""
Open Crafter Link — CLI controller.

A command-line front end over :class:`ocl.Ocl` for driving and inspecting a
running Minecraft client with the mod loaded. Installed as the ``ocl`` console
script; also runnable with ``python -m ocl``.

    ocl telemetry                 # live player state
    ocl vision --dump-dir frames  # RGBD stream
    ocl drive --forward --sprint --hold 2
    ocl drive --look 90 0
    ocl pointcloud                # live 3D RGBD view

Run ``ocl --help`` for the full command list.
"""

from __future__ import annotations

import math
import sys
import time

from . import (
    DEFAULT_INSTRUCT,
    DEFAULT_TELEMETRY,
    DEFAULT_VISION,
    NAN,
    Ocl,
    angle_close,
    decode_telemetry,
    decode_vision,
    frame_to_png,
    frame_to_pointcloud,
    save_ply,
    zmq,
)

def _cmd_telemetry(link: Ocl, args):
    print(f"[telemetry] subscribed to {link.describe_telemetry()}  (Ctrl-C to stop)")
    count, start, last_print = 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    try:
        while deadline is None or time.monotonic() < deadline:
            t = link.read_telemetry(timeout=1.0)
            if t is None:
                print("[telemetry] no message in 1s — is the mod running and in a world?")
                continue
            count += 1
            now = time.monotonic()
            if now - last_print >= 0.25:
                rate = count / (now - start)
                print(f"\r{t}   ~{rate:5.1f} Hz   (n={count})", end="", flush=True)
                last_print = now
    except KeyboardInterrupt:
        pass
    print(f"\n[telemetry] received {count} messages in {time.monotonic()-start:.1f}s")
    return 0


def _cmd_vision(link: Ocl, args):
    import os
    if args.dump_dir:
        os.makedirs(args.dump_dir, exist_ok=True)
    print(f"[vision] subscribed to {link.describe_vision()}  (Ctrl-C to stop)")
    count, dumped, start, last_print = 0, 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    try:
        while deadline is None or time.monotonic() < deadline:
            f = link.read_vision(timeout=1.0)
            if f is None:
                print("\n[vision] no frame in 1s — is the mod running and in a world?")
                continue
            count += 1
            now = time.monotonic()
            if now - last_print >= 0.25:
                rate = count / (now - start)
                print(
                    f"\r[vision] {f.w}x{f.h}  near={f.near:.3f} far={f.far:6.1f}  "
                    f"center_depth={f.center_depth_blocks():6.2f} blocks "
                    f"({f.center_depth_norm():.3f})  ~{rate:5.1f} Hz  (n={count})",
                    end="", flush=True,
                )
                last_print = now
            if args.dump_dir and dumped < args.frames:
                rgb_p = os.path.join(args.dump_dir, f"rgb_{dumped:03d}.png")
                depth_p = os.path.join(args.dump_dir, f"depth_{dumped:03d}.png")
                try:
                    frame_to_png(f, rgb_p, depth_p)
                except ImportError:
                    print("\n[vision] pillow not installed; skipping PNG dump (uv pip install pillow)")
                    args.dump_dir = None
                    continue
                dumped += 1
                if dumped == args.frames:
                    print(f"\n[vision] dumped {dumped} frame(s) to {args.dump_dir}")
    except KeyboardInterrupt:
        pass
    print(f"\n[vision] received {count} frames in {time.monotonic()-start:.1f}s")
    return 0


def _cmd_drive(link: Ocl, args):
    print(f"[drive] instruction endpoint {link.describe_instruct()}")
    if args.demo:
        return _drive_demo(link)
    kw = dict(
        front=args.forward, back=args.back, left=args.left, right=args.right,
        jump=args.jump, sprint=args.sprint, sneak=args.sneak,
        slot=args.slot, attack=args.attack, interact=args.interact,
        yaw=args.look[0] if args.look else NAN,
        pitch=args.look[1] if args.look else NAN,
    )
    if args.hold == 0:
        link.drive(**kw)
        time.sleep(0.1)
        print("[drive] sent 1 instruction(s)")
    else:
        sent = link.hold(args.hold, **kw)
        print(f"[drive] sent {sent} instruction(s)")
    return 0


def _drive_demo(link: Ocl):
    def step(label, seconds=1.0, **kw):
        print(f"[demo] {label}")
        link.hold(seconds, **kw)

    print("[demo] watch the player in-game; each step lasts ~1s")
    step("look around: yaw 0",   yaw=0.0, pitch=0.0)
    step("look around: yaw 90",  yaw=90.0, pitch=0.0)
    step("look around: yaw 180", yaw=180.0, pitch=0.0)
    step("look down",            yaw=180.0, pitch=85.0)
    step("look up",              yaw=180.0, pitch=-85.0)
    step("walk forward",         front=True)
    step("walk forward + sprint", front=True, sprint=True)
    step("strafe left",          left=True)
    step("strafe right",         right=True)
    step("jump",                 jump=True)
    step("sneak",                sneak=True)
    for s in range(9):
        step(f"hotbar slot {s}", seconds=0.4, slot=s)
    step("attack (left click)",  attack=True)
    step("interact (right click)", interact=True)
    print("[demo] done")
    return 0


def _cmd_inventory(link: Ocl, args):
    inv = link.read_inventory(timeout=2.0)
    if inv is None:
        print("[inventory] no telemetry (is the mod running and in a world?)")
        return 1
    if not inv.groups:
        print("[inventory] (no groups)")
        return 0
    for g in inv.groups:
        print(f"{g.name}  ({len(g.slots)} slots)")
        for i, s in enumerate(g.slots):
            if s.empty and not args.all:
                continue
            item = s.item if s.item else "—"
            flag = "" if s.enabled else "  [disabled]"
            print(f"    {i:>2}: {item} x{s.count}{flag}")
    return 0


def _cmd_slot_action(link: Ocl, args):
    """Dispatch the move/pick/put/swap/discard subcommands to the matching Ocl method."""
    op = args.slot_action
    if op == "discard":
        link.discard()
        print("[slot] discard (threw the whole cursor stack)")
    elif op == "swap":
        link.swap(args.group_a, args.index_a, args.group_b, args.index_b)
        print(f"[slot] swap {args.group_a}[{args.index_a}] <-> {args.group_b}[{args.index_b}]")
    elif op == "distribute":
        if len(args.pairs) % 2 != 0 or not args.pairs:
            print("[slot] distribute needs pairs of GROUP INDEX (e.g. inventory 0 inventory 1)")
            return 2
        slots = [(args.pairs[i], int(args.pairs[i + 1])) for i in range(0, len(args.pairs), 2)]
        link.distribute(slots)
        print("[slot] distribute -> " + ", ".join(f"{g}[{i}]" for g, i in slots))
    else:
        getattr(link, op)(args.group, args.index)
        print(f"[slot] {op} {args.group}[{args.index}]")
    time.sleep(0.1)  # let the one-shot instruction land before we exit
    return 0


def _cmd_roundtrip(link: Ocl, args):
    before = link.read_telemetry(timeout=2.0)
    if before is None:
        print("[roundtrip] FAIL: no telemetry (mod running, in a world?)")
        return 1
    print(f"[roundtrip] before: {before}")

    link.hold(0.5, yaw=args.yaw, pitch=args.pitch)

    deadline = time.monotonic() + 2.0
    after = before
    while time.monotonic() < deadline:
        t = link.read_telemetry(timeout=0.2)
        if t is not None:
            after = t
    print(f"[roundtrip] after:  {after}")

    yaw_ok = angle_close(after.yaw, args.yaw, tol=args.tol)
    pitch_ok = abs(after.pitch - max(-90, min(90, args.pitch))) <= args.tol
    if yaw_ok and pitch_ok:
        print(f"[roundtrip] PASS — player rotated to yaw≈{args.yaw}, pitch≈{args.pitch}")
        return 0
    print(f"[roundtrip] FAIL — yaw_ok={yaw_ok} pitch_ok={pitch_ok} (tol={args.tol})")
    return 1


def _cmd_all(link: Ocl, args):
    if link.transport == "uds":
        return _cmd_all_uds(link, args)
    tel = link._telemetry_socket()
    vis = link._vision_socket()
    poller = zmq.Poller()
    poller.register(tel, zmq.POLLIN)
    poller.register(vis, zmq.POLLIN)

    print(f"[all] telemetry={link.describe_telemetry()}  vision={link.describe_vision()}  for {args.seconds}s")
    start = time.monotonic()
    n_tel = n_vis = 0
    last_tel = last_vis = None
    last_print = 0.0
    deadline = start + args.seconds
    while time.monotonic() < deadline:
        for sock, _ in poller.poll(500):
            if sock is tel:
                last_tel = decode_telemetry(tel.recv()); n_tel += 1
            elif sock is vis:
                last_vis = decode_vision(vis.recv()); n_vis += 1
        now = time.monotonic()
        if now - last_print >= 0.25:
            el = now - start
            tr = n_tel / el if el else 0
            vr = n_vis / el if el else 0
            ts = str(last_tel) if last_tel else "—"
            vs = (f"{last_vis.w}x{last_vis.h} depth={last_vis.center_depth_blocks():.1f}b"
                  if last_vis else "—")
            print(f"\r[all] TEL {tr:5.1f}Hz {ts}  |  VIS {vr:5.1f}Hz {vs}   ", end="", flush=True)
            last_print = now
    print()
    print(f"[all] telemetry: {n_tel} msgs ({n_tel/args.seconds:.1f} Hz) | "
          f"vision: {n_vis} frames ({n_vis/args.seconds:.1f} Hz)")
    if n_vis == 0:
        print("[all] NOTE: no vision frames — is the mod running and in a world?")
    return 0 if n_tel > 0 else 1


def _cmd_all_uds(link: Ocl, args):
    """UDS variant of `all`: the two _UdsReader streams don't share a zmq.Poller, so poll each
    with a short timeout in turn (each recv already conflates to the newest frame)."""
    print(f"[all] telemetry={link.describe_telemetry()}  vision={link.describe_vision()}  for {args.seconds}s (uds)")
    start = time.monotonic()
    n_tel = n_vis = 0
    last_tel = last_vis = None
    last_print = 0.0
    deadline = start + args.seconds
    while time.monotonic() < deadline:
        t = link.read_telemetry(timeout=0.05)
        if t is not None:
            last_tel = t; n_tel += 1
        v = link.read_vision(timeout=0.05)
        if v is not None:
            last_vis = v; n_vis += 1
        now = time.monotonic()
        if now - last_print >= 0.25:
            el = now - start
            tr = n_tel / el if el else 0
            vr = n_vis / el if el else 0
            ts = str(last_tel) if last_tel else "—"
            vs = (f"{last_vis.w}x{last_vis.h} depth={last_vis.center_depth_blocks():.1f}b"
                  if last_vis else "—")
            print(f"\r[all] TEL {tr:5.1f}Hz {ts}  |  VIS {vr:5.1f}Hz {vs}   ", end="", flush=True)
            last_print = now
    print()
    print(f"[all] telemetry: {n_tel} msgs ({n_tel/args.seconds:.1f} Hz) | "
          f"vision: {n_vis} frames ({n_vis/args.seconds:.1f} Hz)")
    if n_vis == 0:
        print("[all] NOTE: no vision frames — is the mod running and in a world?")
    return 0 if n_tel > 0 else 1


def _cmd_pointcloud(link: Ocl, args):
    try:
        import numpy as np  # noqa: F401
    except ImportError:
        print("[error] numpy is required for this command:  uv pip install numpy", file=sys.stderr)
        return 2

    if args.save:
        f = link.read_vision(timeout=3.0)
        if f is None:
            print("[pointcloud] no frame in 3s — is the mod running and in a world?")
            return 1
        xyz, col = frame_to_pointcloud(f, args.depth_scale)
        save_ply(args.save, xyz, col)
        print(f"[pointcloud] saved {len(xyz)} points from a {f.w}x{f.h} frame to {args.save}")
        return 0

    return _live_pointcloud(link, args)


def _live_pointcloud(link: Ocl, args):
    import numpy as np
    try:
        import open3d as o3d
        import open3d.visualization.rendering as rendering
    except ImportError:
        print("[pointcloud] open3d not installed; falling back to a one-shot .ply")
        print("             for the live view:  uv pip install open3d matplotlib pyqt5")
        return _fallback_save(link, args)

    plt = _interactive_pyplot()
    if plt is None:
        print("[pointcloud] no usable GUI backend; falling back to a one-shot .ply")
        print("             on Wayland try:  QT_QPA_PLATFORM=wayland  (with pyqt5 installed)")
        return _fallback_save(link, args)

    if link.transport == "uds":
        def _poll_vision():
            return link.read_vision(timeout=0.0)  # newest frame or None, non-blocking
    else:
        sock = link._vision_socket()
        poller = zmq.Poller()
        poller.register(sock, zmq.POLLIN)

        def _poll_vision():
            return decode_vision(sock.recv()) if poller.poll(0) else None

    W, H = 960, 720
    renderer = rendering.OffscreenRenderer(W, H)
    renderer.scene.set_background([0.05, 0.05, 0.08, 1.0])
    renderer.scene.scene.set_sun_light([0.5, -1, -0.5], [1, 1, 1], 60000)
    mat = rendering.MaterialRecord()
    mat.shader = "defaultUnlit"
    mat.point_size = float(args.point_size)

    pcd = o3d.geometry.PointCloud()
    axes = o3d.geometry.TriangleMesh.create_coordinate_frame(size=1.0)
    renderer.scene.add_geometry("axes", axes, rendering.MaterialRecord())

    fig, ax = plt.subplots(figsize=(8, 6))
    fig.canvas.manager.set_window_title("Open Crafter Link — RGBD point cloud")
    ax.axis("off")
    im = ax.imshow(np.zeros((H, W, 3), dtype=np.uint8))
    fig.tight_layout()
    plt.show(block=False)

    have_geom = False
    count, start, last_print = 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    print("[pointcloud] live view open — close the window or Ctrl-C to stop")
    try:
        while deadline is None or time.monotonic() < deadline:
            if not plt.fignum_exists(fig.number):
                break
            f = _poll_vision()
            if f is not None:
                xyz, col = frame_to_pointcloud(f, args.depth_scale)
                if len(xyz):
                    pcd.points = o3d.utility.Vector3dVector(xyz)
                    pcd.colors = o3d.utility.Vector3dVector(col)
                    if renderer.scene.has_geometry("cloud"):
                        renderer.scene.remove_geometry("cloud")
                    renderer.scene.add_geometry("cloud", pcd, mat)
                    have_geom = True
                count += 1

            if have_geom:
                if args.orbit_speed:
                    yaw = args.angle + (time.monotonic() - start) * args.orbit_speed * 6.0
                else:
                    yaw = args.angle
                _aim_iso(renderer, pcd, math.radians(yaw), math.radians(args.elevation), W / H)
                im.set_data(np.asarray(renderer.render_to_image()))
                fig.canvas.draw_idle()
            plt.pause(1.0 / 30.0)

            now = time.monotonic()
            if now - last_print >= 0.5:
                rate = count / (now - start)
                print(f"\r[pointcloud] {len(pcd.points):6d} pts  {rate:4.1f} frames/s in", end="", flush=True)
                last_print = now
    except KeyboardInterrupt:
        pass
    print(f"\n[pointcloud] showed {count} frames in {time.monotonic()-start:.1f}s")
    return 0


def _aim_iso(renderer, pcd, yaw_rad, elev_rad, aspect):
    import numpy as np
    import open3d.visualization.rendering as rendering
    pts = np.asarray(pcd.points)
    if len(pts) == 0:
        return
    center = pts.mean(axis=0)
    ce = math.cos(elev_rad)
    fwd = np.array([ce * math.sin(yaw_rad), math.sin(elev_rad), ce * math.cos(yaw_rad)])
    fwd = fwd / (np.linalg.norm(fwd) or 1.0)
    up_world = np.array([0.0, 1.0, 0.0])
    right = np.cross(up_world, fwd); right /= (np.linalg.norm(right) or 1.0)
    up = np.cross(fwd, right)
    rel = pts - center
    su = rel @ right
    sv = rel @ up
    half_u = (float(su.max() - su.min()) * 0.5) or 1.0
    half_v = (float(sv.max() - sv.min()) * 0.5) or 1.0
    half = max(half_u / max(aspect, 1e-3), half_v) * 1.08
    depth_span = float(np.ptp(rel @ fwd)) + half * 4.0
    cam = renderer.scene.camera
    cam.set_projection(rendering.Camera.Projection.Ortho,
                       -half * aspect, half * aspect, -half, half,
                       0.01, depth_span * 2.0)
    eye = center + fwd * depth_span
    cam.look_at(center, eye, up_world)


def _interactive_pyplot():
    import os
    import matplotlib
    if os.environ.get("WAYLAND_DISPLAY") and not os.environ.get("QT_QPA_PLATFORM"):
        os.environ["QT_QPA_PLATFORM"] = "wayland"
    os.environ.setdefault("QT_LOGGING_RULES", "qt.qpa.wayland=false")
    for backend in ("QtAgg", "TkAgg", "GTK4Agg"):
        try:
            matplotlib.use(backend, force=True)
            import matplotlib.pyplot as plt
            fig = plt.figure()
            plt.close(fig)
            return plt
        except Exception:
            continue
    return None


def _fallback_save(link: Ocl, args):
    path = args.save or "ocl_pointcloud.ply"
    f = link.read_vision(timeout=3.0)
    if f is None:
        print("[pointcloud] no frame in 3s — is the mod running and in a world?")
        return 1
    xyz, col = frame_to_pointcloud(f, args.depth_scale)
    save_ply(path, xyz, col)
    print(f"[pointcloud] saved {len(xyz)} points from a {f.w}x{f.h} frame to {path}")
    return 0


def _build_parser():
    import argparse
    p = argparse.ArgumentParser(
        description="Open Crafter Link — control and inspect a running Minecraft client",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--telemetry", default=DEFAULT_TELEMETRY, help="mod OCLO telemetry endpoint")
    p.add_argument("--vision", default=DEFAULT_VISION, help="mod OCLV vision endpoint")
    p.add_argument("--instruct", default=DEFAULT_INSTRUCT, help="our OCLI instruction bind endpoint")
    p.add_argument("--transport", choices=("tcp", "uds"), default=None,
                   help="wire transport (default: auto-detect from the endpoints; non-tcp:// => uds)")
    p.add_argument("--uds-dir", default=None, dest="uds_dir",
                   help="UDS mode: directory holding the .sock files (default: auto, like the mod)")
    sub = p.add_subparsers(dest="cmd", required=True)

    t = sub.add_parser("telemetry", help="read + decode the OCLO telemetry stream")
    t.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = forever)")
    t.set_defaults(func=_cmd_telemetry)

    v = sub.add_parser("vision", help="read + decode the OCLV RGBD stream")
    v.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = forever)")
    v.add_argument("--dump-dir", help="write rgb_*.png / depth_*.png here (proves no HUD)")
    v.add_argument("--frames", type=int, default=1, help="how many frames to dump")
    v.set_defaults(func=_cmd_vision)

    d = sub.add_parser("drive", help="publish OCLI instructions")
    d.add_argument("--demo", action="store_true", help="walk through every control")
    d.add_argument("--forward", action="store_true")
    d.add_argument("--back", action="store_true")
    d.add_argument("--left", action="store_true")
    d.add_argument("--right", action="store_true")
    d.add_argument("--jump", action="store_true")
    d.add_argument("--sprint", action="store_true")
    d.add_argument("--sneak", action="store_true")
    d.add_argument("--attack", action="store_true")
    d.add_argument("--interact", action="store_true")
    d.add_argument("--slot", type=int, default=-1, help="hotbar slot 0..8 (-1 = no change)")
    d.add_argument("--look", type=float, nargs=2, metavar=("YAW", "PITCH"),
                   help="absolute rotation in degrees")
    d.add_argument("--hold", type=float, default=1.0,
                   help="republish for N seconds to hold a movement (0 = single shot)")
    d.set_defaults(func=_cmd_drive)

    inv = sub.add_parser("inventory", help="read + decode the current screen's inventory (OCLO)")
    inv.add_argument("--all", action="store_true", help="also show empty slots")
    inv.set_defaults(func=_cmd_inventory)

    # Inventory actions. group is a name: hotbar/offhand/armor/inventory/discard, or a container
    # registry id (e.g. generic_9x3, anvil, crafting) for extension slots.
    for name, helptext in (
        ("move", "quick-move (shift-click) a slot"),
        ("pick", "left-click a slot (discard = throw whole cursor stack)"),
        ("put", "right-click a slot (discard = throw one item)"),
        ("drop", "drop one item (vanilla drop key): no screen = selected hotbar; screen = the addressed slot"),
        ("collect", "double-click a slot: gather all matching items onto the cursor"),
    ):
        sp = sub.add_parser(name, help=helptext)
        sp.add_argument("group", nargs="?", default="hotbar",
                        help="slot group: hotbar/offhand/armor/inventory/discard or a container id")
        sp.add_argument("index", type=int, nargs="?", default=0, help="group-local index (default 0)")
        sp.set_defaults(func=_cmd_slot_action, slot_action=name)

    sw = sub.add_parser("swap", help="swap two slots (at least one must be a hotbar slot)")
    sw.add_argument("group_a"); sw.add_argument("index_a", type=int)
    sw.add_argument("group_b"); sw.add_argument("index_b", type=int)
    sw.set_defaults(func=_cmd_slot_action, slot_action="swap")

    ds = sub.add_parser("discard", help="throw the whole cursor stack out of the GUI")
    ds.set_defaults(func=_cmd_slot_action, slot_action="discard")

    di = sub.add_parser("distribute",
                        help="left-drag: split the cursor stack across slots (pass GROUP INDEX pairs)")
    di.add_argument("pairs", nargs="+",
                    help="repeated GROUP INDEX, e.g. 'inventory 0 inventory 1 generic_9x3 2'")
    di.set_defaults(func=_cmd_slot_action, slot_action="distribute")

    r = sub.add_parser("roundtrip", help="send rotation, verify telemetry reflects it")
    r.add_argument("--yaw", type=float, default=90.0)
    r.add_argument("--pitch", type=float, default=0.0)
    r.add_argument("--tol", type=float, default=3.0, help="degrees tolerance")
    r.set_defaults(func=_cmd_roundtrip)

    a = sub.add_parser("all", help="telemetry + vision dashboard")
    a.add_argument("--seconds", type=float, default=10.0)
    a.set_defaults(func=_cmd_all)

    pc = sub.add_parser("pointcloud", help="live 3D RGBD box view (front face = the image, depth = perpendicular)")
    pc.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = until closed)")
    pc.add_argument("--angle", type=float, default=35.0,
                    help="camera azimuth (yaw) in degrees around the box; 0 = head-on, ~35 = 3/4 corner view")
    pc.add_argument("--elevation", type=float, default=25.0,
                    help="camera elevation in degrees above the horizon")
    pc.add_argument("--depth-scale", type=float, default=0.0, dest="depth_scale",
                    help="length of the depth axis: 0 = auto-fit to a roughly cubic box")
    pc.add_argument("--orbit-speed", type=float, default=0.0, dest="orbit_speed",
                    help="if >0, slowly spin the azimuth (deg/sec ×6)")
    pc.add_argument("--point-size", type=float, default=4.0, dest="point_size",
                    help="rendered point size in the live view")
    pc.add_argument("--save", metavar="PLY", help="write one frame to a colored .ply and exit")
    pc.set_defaults(func=_cmd_pointcloud)
    return p


def main(argv=None):
    args = _build_parser().parse_args(argv)
    link = Ocl(telemetry=args.telemetry, vision=args.vision, instruct=args.instruct,
                   transport=args.transport, uds_dir=args.uds_dir)
    try:
        return args.func(link, args)
    except ValueError as e:
        print(f"[error] decode failed: {e}", file=sys.stderr)
        return 2
    finally:
        link.close()


if __name__ == "__main__":
    sys.exit(main())
