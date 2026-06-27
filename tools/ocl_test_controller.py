#!/usr/bin/env python3
"""
Open Crafter Link — test controller.

Stands in for the external "Open Crafter" controller to exercise every link
functionality end-to-end against a running Minecraft client with the mod loaded
(the RGBD stream is always enabled).

Wire roles (from LinkConfig / BinaryCodec):

    mod  PUB  tcp://*:5557   "OCLO"  telemetry      -> we SUB-connect
    mod  PUB  tcp://*:5559   "OCLV"  RGBD vision     -> we SUB-connect
    mod  SUB  tcp://localhost:5558   "OCLI" instr    <- we BIND a PUB and publish

All sockets on both ends use ZMQ_CONFLATE (newest message wins, queue depth 1).

Subcommands:
    telemetry   read + decode the OCLO stream, print yaw/pitch/slot, measure rate
    vision      read + decode the OCLV RGBD stream, measure rate/latency, optionally
                dump frames to PNG (proves no-HUD) and report center-pixel depth
    drive       publish OCLI instructions (move/look/slot/attack/interact); a --demo
                walks through each control so you can watch the player respond
    roundtrip   send a rotation instruction, then read telemetry back and assert the
                player's yaw/pitch actually changed (closes the control loop)
    all         run telemetry + vision readers together (dashboard) for a fixed time
    pointcloud  back-project the RGBD stream into a live 3D point cloud (Open3D), or
                save a single frame to a .ply with --save

Examples:
    python ocl_test_controller.py telemetry
    python ocl_test_controller.py vision --dump-dir /tmp/ocl_frames --frames 3
    python ocl_test_controller.py drive --demo
    python ocl_test_controller.py drive --look 90 0          # face yaw=90
    python ocl_test_controller.py roundtrip --yaw 123 --pitch -20
    python ocl_test_controller.py all --seconds 10
    python ocl_test_controller.py pointcloud                 # live 3D window
    python ocl_test_controller.py pointcloud --save scene.ply
"""

from __future__ import annotations

import argparse
import math
import os
import struct
import sys
import time

try:
    import zmq
except ImportError:
    sys.exit("pyzmq is required:  uv pip install pyzmq   (and 'pillow' for PNG dumps)")


# --------------------------------------------------------------------------- #
# Endpoints (mirror LinkConfig defaults; override via flags or -Docl.* on mod) #
# --------------------------------------------------------------------------- #
DEFAULT_TELEMETRY = "tcp://localhost:5557"   # connect to mod's OCLO PUB
DEFAULT_VISION = "tcp://localhost:5559"      # connect to mod's OCLV PUB
DEFAULT_INSTRUCT = "tcp://*:5558"            # bind our OCLI PUB (mod connects here)


# --------------------------------------------------------------------------- #
# Wire format                                                                  #
# --------------------------------------------------------------------------- #
MAGIC_OUT = b"OCLO"
MAGIC_IN = b"OCLI"
MAGIC_VIS = b"OCLV"
VERSION = 1
VIS_VERSION = 1

# OCLI movement bitmask (BinaryCodec.M_*). NOTE: this order is the codec's, which
# differs from the Java record's field order — follow the codec.
M_FRONT, M_BACK, M_LEFT, M_RIGHT = 1 << 0, 1 << 1, 1 << 2, 1 << 3
M_JUMP, M_SPRINT, M_SNEAK = 1 << 4, 1 << 5, 1 << 6
A_ATTACK, A_INTERACT = 1 << 0, 1 << 1

NAN = float("nan")


# --------------------------------------------------------------------------- #
# Decoders                                                                     #
# --------------------------------------------------------------------------- #
class Telemetry:
    """Decoded OCLO message."""

    __slots__ = ("yaw", "pitch", "slot")

    def __init__(self, yaw, pitch, slot):
        self.yaw, self.pitch, self.slot = yaw, pitch, slot

    def __str__(self):
        return f"yaw={self.yaw:8.2f}  pitch={self.pitch:7.2f}  slot={self.slot}"


def decode_telemetry(buf: bytes) -> Telemetry:
    # magic[4] ver[1] yaw[f] pitch[f] slot[i]
    if len(buf) < 5 + 4 + 4 + 4:
        raise ValueError(f"OCLO too short: {len(buf)} bytes")
    if buf[:4] != MAGIC_OUT:
        raise ValueError(f"bad OCLO magic: {buf[:4]!r}")
    if buf[4] != VERSION:
        raise ValueError(f"bad OCLO version: {buf[4]}")
    # <f f i  ->  yaw, pitch, slot
    yaw, pitch, slot = struct.unpack_from("<ffi", buf, 5)
    return Telemetry(yaw, pitch, slot)


class VisionFrame:
    """Decoded OCLV message: RGB (w*h*3) + depth (w*h), both float32 normalized 0..1."""

    __slots__ = ("w", "h", "near", "far", "rgb", "depth")

    def __init__(self, w, h, near, far, rgb, depth):
        self.w, self.h, self.near, self.far = w, h, near, far
        self.rgb, self.depth = rgb, depth  # tuples of float

    def center_depth_norm(self) -> float:
        return self.depth[(self.h // 2) * self.w + (self.w // 2)]

    def center_depth_blocks(self) -> float:
        return self.center_depth_norm() * self.far


def decode_vision(buf: bytes) -> VisionFrame:
    # magic[4] ver[1] w[i] h[i] near[f] far[f] rgb[w*h*3 f] depth[w*h f]
    head = 5 + 4 + 4 + 4 + 4
    if len(buf) < head:
        raise ValueError(f"OCLV too short: {len(buf)} bytes")
    if buf[:4] != MAGIC_VIS:
        raise ValueError(f"bad OCLV magic: {buf[:4]!r}")
    if buf[4] != VIS_VERSION:
        raise ValueError(f"bad OCLV version: {buf[4]}")
    w, h, near, far = struct.unpack_from("<iiff", buf, 5)
    n_rgb = w * h * 3
    n_depth = w * h
    want = head + (n_rgb + n_depth) * 4
    if len(buf) != want:
        raise ValueError(f"OCLV length mismatch: got {len(buf)}, expected {want} (w={w} h={h})")
    rgb = struct.unpack_from(f"<{n_rgb}f", buf, head)
    depth = struct.unpack_from(f"<{n_depth}f", buf, head + n_rgb * 4)
    return VisionFrame(w, h, near, far, rgb, depth)


def encode_instruction(
    *,
    front=False, back=False, left=False, right=False,
    jump=False, sprint=False, sneak=False,
    slot=-1, attack=False, interact=False,
    yaw=NAN, pitch=NAN,
) -> bytes:
    move = 0
    move |= M_FRONT if front else 0
    move |= M_BACK if back else 0
    move |= M_LEFT if left else 0
    move |= M_RIGHT if right else 0
    move |= M_JUMP if jump else 0
    move |= M_SPRINT if sprint else 0
    move |= M_SNEAK if sneak else 0
    action = (A_ATTACK if attack else 0) | (A_INTERACT if interact else 0)
    # magic[4] ver[1] move[B] slot[i] action[B] yaw[f] pitch[f]
    return MAGIC_IN + struct.pack("<BBiBff", VERSION, move, slot, action, yaw, pitch)


# --------------------------------------------------------------------------- #
# Socket helpers                                                               #
# --------------------------------------------------------------------------- #
def sub_socket(ctx, endpoint):
    s = ctx.socket(zmq.SUB)
    s.setsockopt(zmq.CONFLATE, 1)        # match the mod: newest wins
    s.setsockopt(zmq.SUBSCRIBE, b"")     # CONFLATE requires subscribe-all
    s.connect(endpoint)
    return s


def pub_socket(ctx, endpoint):
    s = ctx.socket(zmq.PUB)
    s.setsockopt(zmq.CONFLATE, 1)
    s.bind(endpoint)
    return s


# --------------------------------------------------------------------------- #
# Subcommands                                                                  #
# --------------------------------------------------------------------------- #
def cmd_telemetry(args):
    ctx = zmq.Context.instance()
    sock = sub_socket(ctx, args.telemetry)
    poller = zmq.Poller()
    poller.register(sock, zmq.POLLIN)

    print(f"[telemetry] subscribed to {args.telemetry}  (Ctrl-C to stop)")
    count, start, last_print = 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    try:
        while deadline is None or time.monotonic() < deadline:
            if not poller.poll(1000):
                print("[telemetry] no message in 1s — is the mod running and in a world?")
                continue
            t = decode_telemetry(sock.recv())
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


def cmd_vision(args):
    ctx = zmq.Context.instance()
    sock = sub_socket(ctx, args.vision)
    poller = zmq.Poller()
    poller.register(sock, zmq.POLLIN)

    if args.dump_dir:
        os.makedirs(args.dump_dir, exist_ok=True)

    print(f"[vision] subscribed to {args.vision}  (Ctrl-C to stop)")
    count, dumped, start, last_print = 0, 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    try:
        while deadline is None or time.monotonic() < deadline:
            if not poller.poll(1000):
                print("\n[vision] no frame in 1s — is the client in a world?")
                continue
            t_recv = time.monotonic()
            f = decode_vision(sock.recv())
            count += 1
            now = time.monotonic()
            if now - last_print >= 0.25:
                rate = count / (now - start)
                cd = f.center_depth_blocks()
                print(
                    f"\r[vision] {f.w}x{f.h}  near={f.near:.3f} far={f.far:6.1f}  "
                    f"center_depth={cd:6.2f} blocks ({f.center_depth_norm():.3f})  "
                    f"~{rate:5.1f} Hz  (n={count})",
                    end="", flush=True,
                )
                last_print = now
            if args.dump_dir and dumped < args.frames:
                _dump_frame(f, args.dump_dir, dumped, t_recv)
                dumped += 1
                if dumped == args.frames:
                    print(f"\n[vision] dumped {dumped} frame(s) to {args.dump_dir}")
    except KeyboardInterrupt:
        pass
    print(f"\n[vision] received {count} frames in {time.monotonic()-start:.1f}s")
    return 0


def _dump_frame(f: VisionFrame, out_dir: str, idx: int, t_recv: float):
    """Write RGB and a grayscale depth PNG. Proves the frame has no HUD overlay."""
    try:
        from PIL import Image
    except ImportError:
        print("\n[vision] pillow not installed; skipping PNG dump (uv pip install pillow)")
        return
    w, h = f.w, f.h
    rgb_bytes = bytes(max(0, min(255, int(c * 255 + 0.5))) for c in f.rgb)
    Image.frombytes("RGB", (w, h), rgb_bytes).save(os.path.join(out_dir, f"rgb_{idx:03d}.png"))
    # depth is normalized 0..1; nearer = darker
    depth_bytes = bytes(max(0, min(255, int(d * 255 + 0.5))) for d in f.depth)
    Image.frombytes("L", (w, h), depth_bytes).save(os.path.join(out_dir, f"depth_{idx:03d}.png"))


# --------------------------------------------------------------------------- #
# RGBD -> 3D point cloud                                                       #
# --------------------------------------------------------------------------- #
def _backproject(f: VisionFrame, fov_deg: float, max_blocks: float):
    """
    Back-project an OCLV frame into a colored point cloud in camera space.

    The mod ships depth as ``norm = linear_eye_distance / far`` (see VisionCapture.convert),
    so the forward distance of each pixel is simply ``Z = norm * far`` blocks. With a pinhole
    model whose focal length comes from the vertical FOV, the lateral coordinates are
    ``X = (u - cx) * Z / f`` and ``Y = (v - cy) * Z / f``.

    Axes (right-handed, OpenGL/Open3D convention): +X right, +Y up, +Z toward the viewer, so
    the scene in front of the player sits at negative Z. Pixels at or beyond ``max_blocks``
    (and the depth==1 "sky" clamp) are dropped so the far plane doesn't smear into a wall.

    Returns ``(xyz, rgb)`` as numpy float arrays of shape (N, 3), or (empty, empty).
    """
    import numpy as np

    w, h, far = f.w, f.h, f.far
    depth = np.asarray(f.depth, dtype=np.float32).reshape(h, w)
    rgb = np.asarray(f.rgb, dtype=np.float32).reshape(h, w, 3)

    z_blocks = depth * far  # forward distance per pixel
    # Focal length in pixels from the vertical FOV; cx/cy at the image center.
    f_px = (h / 2.0) / math.tan(math.radians(fov_deg) / 2.0)
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0

    us = np.arange(w, dtype=np.float32)[None, :].repeat(h, axis=0)
    vs = np.arange(h, dtype=np.float32)[:, None].repeat(w, axis=1)

    x = (us - cx) * z_blocks / f_px
    y = -(vs - cy) * z_blocks / f_px   # image v grows downward; flip to make +Y up
    z = -z_blocks                      # scene in front of the player -> negative Z

    # Drop the sky / far-plane clamp and anything past the cutoff.
    keep = (depth < 0.999) & (z_blocks <= max_blocks) & (z_blocks > 0.0)
    xyz = np.stack((x[keep], y[keep], z[keep]), axis=1)
    col = rgb[keep]
    return xyz, col


def cmd_pointcloud(args):
    """Live (or one-shot) 3D point-cloud view of the OCLV RGBD stream."""
    try:
        import numpy as np  # noqa: F401  (used by _backproject)
    except ImportError:
        return _missing("numpy", "uv pip install numpy")

    # --save writes a single .ply and needs no GUI/Open3D display — handy over SSH.
    save_only = bool(args.save)

    o3d = None
    if not save_only:
        try:
            import open3d as o3d  # noqa: F811
        except ImportError:
            print("[pointcloud] open3d not installed; falling back to one-shot --save")
            print("             for a live window:  uv pip install open3d")
            if not args.save:
                args.save = "ocl_pointcloud.ply"
            save_only = True

    ctx = zmq.Context.instance()
    sock = sub_socket(ctx, args.vision)
    poller = zmq.Poller()
    poller.register(sock, zmq.POLLIN)
    print(f"[pointcloud] subscribed to {args.vision}  fov={args.fov}°  max={args.max_blocks}b")

    # ---- one-shot save path (no live window) ----
    if save_only:
        if not poller.poll(3000):
            print("[pointcloud] no frame in 3s — is the client in a world?")
            return 1
        f = decode_vision(sock.recv())
        xyz, col = _backproject(f, args.fov, args.max_blocks)
        _save_ply(args.save, xyz, col)
        print(f"[pointcloud] saved {len(xyz)} points from a {f.w}x{f.h} frame to {args.save}")
        return 0

    # ---- live window path ----
    pcd = o3d.geometry.PointCloud()
    vis = o3d.visualization.Visualizer()
    vis.create_window(window_name="Open Crafter Link — RGBD", width=960, height=720)
    # A small frame at the camera origin orients the viewer (red=+X, green=+Y, blue=+Z).
    vis.add_geometry(o3d.geometry.TriangleMesh.create_coordinate_frame(size=1.0))

    first = True
    count, start, last_print = 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    print("[pointcloud] live window open — close it or Ctrl-C to stop")
    try:
        while deadline is None or time.monotonic() < deadline:
            if poller.poll(0):
                f = decode_vision(sock.recv())
                xyz, col = _backproject(f, args.fov, args.max_blocks)
                pcd.points = o3d.utility.Vector3dVector(xyz)
                pcd.colors = o3d.utility.Vector3dVector(col)
                if first and len(xyz):
                    vis.add_geometry(pcd)          # add once it has geometry, then reset view
                    vis.reset_view_point(True)
                    first = False
                else:
                    vis.update_geometry(pcd)
                count += 1
                now = time.monotonic()
                if now - last_print >= 0.5:
                    rate = count / (now - start)
                    print(f"\r[pointcloud] {f.w}x{f.h}  {len(xyz):6d} pts  ~{rate:4.1f} Hz",
                          end="", flush=True)
                    last_print = now
            # Pump the GUI; returns False when the user closes the window.
            if not vis.poll_events():
                break
            vis.update_renderer()
    except KeyboardInterrupt:
        pass
    vis.destroy_window()
    print(f"\n[pointcloud] rendered {count} frames in {time.monotonic()-start:.1f}s")
    return 0


def _save_ply(path: str, xyz, col):
    """Write a colored ASCII PLY (no Open3D dependency) so the result opens anywhere."""
    n = len(xyz)
    with open(path, "w") as fh:
        fh.write("ply\nformat ascii 1.0\n")
        fh.write(f"element vertex {n}\n")
        fh.write("property float x\nproperty float y\nproperty float z\n")
        fh.write("property uchar red\nproperty uchar green\nproperty uchar blue\n")
        fh.write("end_header\n")
        for (x, y, z), (r, g, b) in zip(xyz, col):
            fh.write(f"{x:.4f} {y:.4f} {z:.4f} "
                     f"{int(r*255+0.5)} {int(g*255+0.5)} {int(b*255+0.5)}\n")


def _missing(pkg: str, hint: str):
    print(f"[error] {pkg} is required for this command:  {hint}", file=sys.stderr)
    return 2


def cmd_drive(args):
    ctx = zmq.Context.instance()
    pub = pub_socket(ctx, args.instruct)
    print(f"[drive] bound OCLI PUB on {args.instruct}")
    # PUB/SUB needs a moment for the mod's SUB to (re)connect before messages land.
    time.sleep(0.5)

    if args.demo:
        return _drive_demo(pub, args)

    instr = encode_instruction(
        front=args.forward, back=args.back, left=args.left, right=args.right,
        jump=args.jump, sprint=args.sprint, sneak=args.sneak,
        slot=args.slot, attack=args.attack, interact=args.interact,
        yaw=args.look[0] if args.look else NAN,
        pitch=args.look[1] if args.look else NAN,
    )
    # The mod consumes one instruction per tick (20 Hz) and releases otherwise, so to
    # *hold* a movement we must republish every tick for the requested duration.
    end = time.monotonic() + args.hold
    sent = 0
    while time.monotonic() < end:
        pub.send(instr)
        sent += 1
        time.sleep(1.0 / 30.0)
    if args.hold == 0:
        pub.send(instr)
        sent = 1
        time.sleep(0.1)
    print(f"[drive] sent {sent} instruction(s)")
    return 0


def _drive_demo(pub, args):
    def hold(label, seconds=1.0, **kw):
        print(f"[demo] {label}")
        instr = encode_instruction(**kw)
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            pub.send(instr)
            time.sleep(1.0 / 30.0)

    print("[demo] watch the player in-game; each step lasts ~1s")
    hold("look around: yaw 0",   yaw=0.0, pitch=0.0)
    hold("look around: yaw 90",  yaw=90.0, pitch=0.0)
    hold("look around: yaw 180", yaw=180.0, pitch=0.0)
    hold("look down",            yaw=180.0, pitch=85.0)
    hold("look up",              yaw=180.0, pitch=-85.0)
    hold("walk forward",         front=True)
    hold("walk forward + sprint", front=True, sprint=True)
    hold("strafe left",          left=True)
    hold("strafe right",         right=True)
    hold("jump",                 jump=True)
    hold("sneak",                sneak=True)
    for s in range(9):
        hold(f"hotbar slot {s}", seconds=0.4, slot=s)
    hold("attack (left click)",  attack=True)
    hold("interact (right click)", interact=True)
    print("[demo] done")
    return 0


def cmd_roundtrip(args):
    """Send an absolute rotation, then verify telemetry reports it — closes the loop."""
    ctx = zmq.Context.instance()
    pub = pub_socket(ctx, args.instruct)
    sub = sub_socket(ctx, args.telemetry)
    poller = zmq.Poller()
    poller.register(sub, zmq.POLLIN)
    time.sleep(0.5)  # let SUB connect

    # baseline
    if not poller.poll(2000):
        print("[roundtrip] FAIL: no telemetry (mod running, in a world?)")
        return 1
    before = decode_telemetry(sub.recv())
    print(f"[roundtrip] before: {before}")

    instr = encode_instruction(yaw=args.yaw, pitch=args.pitch)
    for _ in range(15):                 # republish a few ticks so one lands
        pub.send(instr)
        time.sleep(1.0 / 30.0)

    deadline = time.monotonic() + 2.0
    after = before
    while time.monotonic() < deadline:
        if poller.poll(200):
            after = decode_telemetry(sub.recv())
    print(f"[roundtrip] after:  {after}")

    yaw_ok = _angle_close(after.yaw, args.yaw, tol=args.tol)
    pitch_ok = abs(after.pitch - max(-90, min(90, args.pitch))) <= args.tol
    if yaw_ok and pitch_ok:
        print(f"[roundtrip] PASS — player rotated to yaw≈{args.yaw}, pitch≈{args.pitch}")
        return 0
    print(f"[roundtrip] FAIL — yaw_ok={yaw_ok} pitch_ok={pitch_ok} (tol={args.tol})")
    return 1


def _angle_close(a, b, tol):
    d = (a - b + 180.0) % 360.0 - 180.0
    return abs(d) <= tol


def cmd_all(args):
    """Dashboard: read telemetry and vision together and report independent rates."""
    ctx = zmq.Context.instance()
    tel = sub_socket(ctx, args.telemetry)
    vis = sub_socket(ctx, args.vision)
    poller = zmq.Poller()
    poller.register(tel, zmq.POLLIN)
    poller.register(vis, zmq.POLLIN)

    print(f"[all] telemetry={args.telemetry}  vision={args.vision}  for {args.seconds}s")
    start = time.monotonic()
    n_tel = n_vis = 0
    last_tel = None
    last_vis = None
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
            print(f"\r[all] TEL {tr:5.1f}Hz {ts}  |  VIS {vr:5.1f}Hz {vs}   ",
                  end="", flush=True)
            last_print = now
    print()
    print(f"[all] telemetry: {n_tel} msgs ({n_tel/args.seconds:.1f} Hz) | "
          f"vision: {n_vis} frames ({n_vis/args.seconds:.1f} Hz)")
    ok = n_tel > 0
    if n_vis == 0:
        print("[all] NOTE: no vision frames — is the client in a world?")
    return 0 if ok else 1


# --------------------------------------------------------------------------- #
# CLI                                                                          #
# --------------------------------------------------------------------------- #
def build_parser():
    p = argparse.ArgumentParser(
        description="Open Crafter Link test controller",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--telemetry", default=DEFAULT_TELEMETRY, help="mod OCLO PUB endpoint")
    p.add_argument("--vision", default=DEFAULT_VISION, help="mod OCLV PUB endpoint")
    p.add_argument("--instruct", default=DEFAULT_INSTRUCT, help="our OCLI PUB bind endpoint")
    sub = p.add_subparsers(dest="cmd", required=True)

    t = sub.add_parser("telemetry", help="read + decode the OCLO telemetry stream")
    t.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = forever)")
    t.set_defaults(func=cmd_telemetry)

    v = sub.add_parser("vision", help="read + decode the OCLV RGBD stream")
    v.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = forever)")
    v.add_argument("--dump-dir", help="write rgb_*.png / depth_*.png here (proves no HUD)")
    v.add_argument("--frames", type=int, default=1, help="how many frames to dump")
    v.set_defaults(func=cmd_vision)

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
    d.set_defaults(func=cmd_drive)

    r = sub.add_parser("roundtrip", help="send rotation, verify telemetry reflects it")
    r.add_argument("--yaw", type=float, default=90.0)
    r.add_argument("--pitch", type=float, default=0.0)
    r.add_argument("--tol", type=float, default=3.0, help="degrees tolerance")
    r.set_defaults(func=cmd_roundtrip)

    a = sub.add_parser("all", help="telemetry + vision dashboard")
    a.add_argument("--seconds", type=float, default=10.0)
    a.set_defaults(func=cmd_all)

    pc = sub.add_parser("pointcloud", help="live 3D point-cloud view of the RGBD stream")
    pc.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = until closed)")
    pc.add_argument("--fov", type=float, default=70.0,
                    help="vertical field of view in degrees (Minecraft's default is 70)")
    pc.add_argument("--max-blocks", type=float, default=64.0, dest="max_blocks",
                    help="drop points farther than this (blocks) so the far plane doesn't smear")
    pc.add_argument("--save", metavar="PLY",
                    help="write one frame to a colored .ply and exit (no GUI needed)")
    pc.set_defaults(func=cmd_pointcloud)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except ValueError as e:
        print(f"[error] decode failed: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
