#!/usr/bin/env python3
"""
Open Crafter Link — single-file client library.

Copy this one file into any Python project and you have the full Open Crafter Link
API: read player telemetry, read the RGBD vision stream, and drive the player.

    from ocl_link import OclLink

    with OclLink() as link:
        t = link.read_telemetry()                 # newest player state
        print(t.yaw, t.pitch, t.slot)

        link.drive(forward=True, sprint=True)     # one instruction (hold from your loop)
        link.look(yaw=90, pitch=0)                # absolute rotation
        link.set_slot(3)                          # select hotbar slot

        f = link.read_vision()                    # newest RGBD frame (needs -Docl.vision=true)
        print(f.w, f.h, f.center_depth_blocks())

Everything is in this module — only `pyzmq` is required. `numpy`/`pillow` are used
lazily and only by the optional helpers (`VisionFrame.to_numpy`, `frame_to_png`,
`frame_to_pointcloud`).

This file is *also* a CLI test controller; run `python ocl_link.py --help`.

Wire roles (from the mod's LinkConfig / BinaryCodec):

    mod  PUB  tcp://*:5557   "OCLO"  telemetry      -> we SUB-connect
    mod  PUB  tcp://*:5559   "OCLV"  RGBD vision     -> we SUB-connect
    mod  SUB  tcp://localhost:5558   "OCLI" instr    <- we BIND a PUB and publish

All sockets on both ends use ZMQ_CONFLATE (newest message wins, queue depth 1).
"""

from __future__ import annotations

import math
import struct
import sys
import time
from dataclasses import dataclass
from typing import Optional, Sequence

try:
    import zmq
except ImportError:  # pragma: no cover
    raise ImportError("pyzmq is required:  uv pip install pyzmq") from None


__all__ = [
    "OclLink",
    "Telemetry",
    "VisionFrame",
    "Instruction",
    "decode_telemetry",
    "decode_vision",
    "encode_instruction",
    "DEFAULT_TELEMETRY",
    "DEFAULT_VISION",
    "DEFAULT_INSTRUCT",
]


# --------------------------------------------------------------------------- #
# Endpoints (mirror LinkConfig defaults; override in the constructor)          #
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
# Messages                                                                     #
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Telemetry:
    """Decoded OCLO message: the player's current state."""

    yaw: float
    pitch: float
    slot: int

    def __str__(self) -> str:
        return f"yaw={self.yaw:8.2f}  pitch={self.pitch:7.2f}  slot={self.slot}"


@dataclass(frozen=True)
class VisionFrame:
    """Decoded OCLV message: RGB (w*h*3) + depth (w*h), both float32 normalized 0..1.

    `near`/`far` are the eye-space clip planes in blocks; depth on the wire is
    linear eye-space distance ÷ far, so `depth * far` is distance in blocks.
    """

    w: int
    h: int
    near: float
    far: float
    rgb: Sequence[float]    # length w*h*3, row-major RGB, 0..1
    depth: Sequence[float]  # length w*h,   row-major,     0..1 (dist / far)

    def center_depth_norm(self) -> float:
        return self.depth[(self.h // 2) * self.w + (self.w // 2)]

    def center_depth_blocks(self) -> float:
        return self.center_depth_norm() * self.far

    def depth_at(self, col: int, row: int) -> float:
        """Distance in blocks to pixel (col, row)."""
        return self.depth[row * self.w + col] * self.far

    def to_numpy(self):
        """Return `(rgb, depth)` numpy arrays: rgb (h, w, 3) float32, depth (h, w) float32.

        Requires numpy.
        """
        import numpy as np
        rgb = np.asarray(self.rgb, dtype=np.float32).reshape(self.h, self.w, 3)
        depth = np.asarray(self.depth, dtype=np.float32).reshape(self.h, self.w)
        return rgb, depth


@dataclass(frozen=True)
class Instruction:
    """An OCLI control message. Build with kwargs, send via `OclLink.send`."""

    front: bool = False
    back: bool = False
    left: bool = False
    right: bool = False
    jump: bool = False
    sprint: bool = False
    sneak: bool = False
    slot: int = -1          # hotbar slot 0..8, -1 = no change
    attack: bool = False
    interact: bool = False
    yaw: float = NAN        # absolute rotation in degrees; NaN = no change
    pitch: float = NAN

    def encode(self) -> bytes:
        return encode_instruction(
            front=self.front, back=self.back, left=self.left, right=self.right,
            jump=self.jump, sprint=self.sprint, sneak=self.sneak,
            slot=self.slot, attack=self.attack, interact=self.interact,
            yaw=self.yaw, pitch=self.pitch,
        )


# --------------------------------------------------------------------------- #
# Codec                                                                        #
# --------------------------------------------------------------------------- #
def decode_telemetry(buf: bytes) -> Telemetry:
    # magic[4] ver[1] yaw[f] pitch[f] slot[i]
    if len(buf) < 5 + 4 + 4 + 4:
        raise ValueError(f"OCLO too short: {len(buf)} bytes")
    if buf[:4] != MAGIC_OUT:
        raise ValueError(f"bad OCLO magic: {buf[:4]!r}")
    if buf[4] != VERSION:
        raise ValueError(f"bad OCLO version: {buf[4]}")
    yaw, pitch, slot = struct.unpack_from("<ffi", buf, 5)
    return Telemetry(yaw, pitch, slot)


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
# Client                                                                       #
# --------------------------------------------------------------------------- #
class OclLink:
    """High-level client for the Open Crafter Link.

    Lazily creates ZMQ sockets the first time each stream is used, so you only
    pay for what you touch. Use as a context manager (or call `close()`) to
    release sockets.

        with OclLink() as link:
            print(link.read_telemetry())
            link.look(90, 0)
    """

    def __init__(
        self,
        telemetry: str = DEFAULT_TELEMETRY,
        vision: str = DEFAULT_VISION,
        instruct: str = DEFAULT_INSTRUCT,
        *,
        context: "Optional[zmq.Context]" = None,
    ):
        self.telemetry_endpoint = telemetry
        self.vision_endpoint = vision
        self.instruct_endpoint = instruct
        self._owns_ctx = context is None
        self._ctx = context or zmq.Context.instance()
        self._tel_sock = None
        self._vis_sock = None
        self._pub_sock = None
        self._pub_warmed = False

    # ---- socket factories -------------------------------------------------- #
    def _sub(self, endpoint):
        s = self._ctx.socket(zmq.SUB)
        s.setsockopt(zmq.CONFLATE, 1)     # match the mod: newest wins
        s.setsockopt(zmq.SUBSCRIBE, b"")  # CONFLATE requires subscribe-all
        s.connect(endpoint)
        return s

    def _telemetry_socket(self):
        if self._tel_sock is None:
            self._tel_sock = self._sub(self.telemetry_endpoint)
        return self._tel_sock

    def _vision_socket(self):
        if self._vis_sock is None:
            self._vis_sock = self._sub(self.vision_endpoint)
        return self._vis_sock

    def _instruct_socket(self):
        if self._pub_sock is None:
            s = self._ctx.socket(zmq.PUB)
            s.setsockopt(zmq.CONFLATE, 1)
            s.bind(self.instruct_endpoint)
            self._pub_sock = s
        return self._pub_sock

    # ---- reading ----------------------------------------------------------- #
    def read_telemetry(self, timeout: float = 1.0) -> Optional[Telemetry]:
        """Return the newest player telemetry, or None if none arrived in `timeout` seconds."""
        buf = self._recv(self._telemetry_socket(), timeout)
        return decode_telemetry(buf) if buf is not None else None

    def read_vision(self, timeout: float = 1.0) -> Optional[VisionFrame]:
        """Return the newest RGBD frame, or None within `timeout`.

        Requires the client to be launched with -Docl.vision=true and to be in a world.
        """
        buf = self._recv(self._vision_socket(), timeout)
        return decode_vision(buf) if buf is not None else None

    def _recv(self, sock, timeout: float):
        poller = zmq.Poller()
        poller.register(sock, zmq.POLLIN)
        ms = None if timeout is None else int(timeout * 1000)
        if poller.poll(ms):
            return sock.recv()
        return None

    def telemetry_stream(self, timeout: float = 1.0):
        """Yield Telemetry forever (None on each timeout gap)."""
        while True:
            yield self.read_telemetry(timeout)

    def vision_stream(self, timeout: float = 1.0):
        """Yield VisionFrame forever (None on each timeout gap)."""
        while True:
            yield self.read_vision(timeout)

    # ---- driving ----------------------------------------------------------- #
    def send(self, instr: Instruction) -> None:
        """Send one instruction. The mod applies one per tick (~20 Hz); to *hold* a
        movement, call this every tick (see `hold`)."""
        self._send_bytes(instr.encode())

    def _send_bytes(self, data: bytes) -> None:
        sock = self._instruct_socket()
        if not self._pub_warmed:
            # PUB/SUB needs a moment for the mod's SUB to (re)connect before the
            # first message lands; otherwise it's silently dropped.
            time.sleep(0.3)
            self._pub_warmed = True
        sock.send(data)

    def drive(self, **kwargs) -> Instruction:
        """Send a one-shot instruction built from keyword fields (see `Instruction`).
        Returns the Instruction sent."""
        instr = Instruction(**kwargs)
        self.send(instr)
        return instr

    def hold(self, seconds: float, *, hz: float = 30.0, **kwargs) -> int:
        """Republish an instruction every tick for `seconds` so a movement is *held*.
        Returns the number of messages sent."""
        instr = Instruction(**kwargs)
        end = time.monotonic() + seconds
        sent = 0
        period = 1.0 / hz
        while time.monotonic() < end:
            self.send(instr)
            sent += 1
            time.sleep(period)
        return sent

    def look(self, yaw: float, pitch: float, *, hold: float = 0.0) -> None:
        """Set absolute rotation (degrees). With `hold>0`, republish to ensure it lands."""
        if hold > 0:
            self.hold(hold, yaw=yaw, pitch=pitch)
        else:
            self.drive(yaw=yaw, pitch=pitch)

    def set_slot(self, slot: int) -> None:
        """Select a hotbar slot 0..8."""
        self.drive(slot=slot)

    def attack(self) -> None:
        self.drive(attack=True)

    def interact(self) -> None:
        self.drive(interact=True)

    # ---- lifecycle --------------------------------------------------------- #
    def close(self) -> None:
        for s in (self._tel_sock, self._vis_sock, self._pub_sock):
            if s is not None:
                s.close(0)
        self._tel_sock = self._vis_sock = self._pub_sock = None
        if self._owns_ctx and self._ctx is not zmq.Context.instance():
            self._ctx.term()

    def __enter__(self) -> "OclLink":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


# --------------------------------------------------------------------------- #
# Optional helpers (lazy imports — only needed if you call them)               #
# --------------------------------------------------------------------------- #
def frame_to_png(frame: VisionFrame, rgb_path: str, depth_path: Optional[str] = None) -> None:
    """Write the frame's RGB (and optionally grayscale depth) to PNG. Requires pillow."""
    from PIL import Image
    w, h = frame.w, frame.h
    rgb_bytes = bytes(max(0, min(255, int(c * 255 + 0.5))) for c in frame.rgb)
    Image.frombytes("RGB", (w, h), rgb_bytes).save(rgb_path)
    if depth_path:
        depth_bytes = bytes(max(0, min(255, int(d * 255 + 0.5))) for d in frame.depth)
        Image.frombytes("L", (w, h), depth_bytes).save(depth_path)


def frame_to_pointcloud(frame: VisionFrame, depth_scale: float = 0.0):
    """Build an extruded image-plane point cloud from an RGBD frame.

    Returns `(xyz, rgb)` numpy arrays of shape (w*h, 3). Every pixel keeps its
    position on a flat front face (the image as seen in-game) and is pushed
    *backward* by its depth. The depth axis auto-fits the real (non-sky)
    geometry's distance range. `depth_scale<=0` auto-fits to a roughly cubic box.

    Geometry (right-handed, +X right, +Y up, depth recedes to -Z):
      X = (col / (w-1) - 0.5) * aspect
      Y =  0.5 - row / (h-1)
      Z = -t * scale   where t maps min..max real distance onto [0,1]
    """
    import numpy as np

    w, h = frame.w, frame.h
    aspect = w / h
    scale = depth_scale if depth_scale > 0.0 else aspect

    depth = np.asarray(frame.depth, dtype=np.float32).reshape(h, w)
    rgb = np.asarray(frame.rgb, dtype=np.float32).reshape(h, w, 3)

    cols = np.arange(w, dtype=np.float32)[None, :]
    rows = np.arange(h, dtype=np.float32)[:, None]
    x = np.broadcast_to(((cols / max(w - 1, 1)) - 0.5) * aspect, (h, w))
    y = np.broadcast_to(0.5 - rows / max(h - 1, 1), (h, w))

    sky = depth >= 0.999
    real = depth[~sky]
    if real.size:
        d_lo, d_hi = float(real.min()), float(real.max())
    else:
        d_lo, d_hi = 0.0, 1.0
    span = (d_hi - d_lo) or 1e-6
    t = np.clip((depth - d_lo) / span, 0.0, 1.0)
    t[sky] = 1.0
    z = -t * scale

    xyz = np.stack((x.ravel(), y.ravel(), z.ravel()), axis=1)
    col = rgb.reshape(-1, 3)
    return xyz, col


def save_ply(path: str, xyz, col) -> None:
    """Write a colored ASCII PLY (no extra deps) so the result opens anywhere."""
    with open(path, "w") as fh:
        fh.write("ply\nformat ascii 1.0\n")
        fh.write(f"element vertex {len(xyz)}\n")
        fh.write("property float x\nproperty float y\nproperty float z\n")
        fh.write("property uchar red\nproperty uchar green\nproperty uchar blue\n")
        fh.write("end_header\n")
        for (x, y, z), (r, g, b) in zip(xyz, col):
            fh.write(f"{x:.4f} {y:.4f} {z:.4f} "
                     f"{int(r*255+0.5)} {int(g*255+0.5)} {int(b*255+0.5)}\n")


def angle_close(a: float, b: float, tol: float) -> bool:
    """True if angles a and b (degrees) are within tol, accounting for wraparound."""
    d = (a - b + 180.0) % 360.0 - 180.0
    return abs(d) <= tol


# --------------------------------------------------------------------------- #
# CLI (kept so this file is also a self-contained test controller)            #
# --------------------------------------------------------------------------- #
def _cmd_telemetry(link: OclLink, args):
    print(f"[telemetry] subscribed to {link.telemetry_endpoint}  (Ctrl-C to stop)")
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


def _cmd_vision(link: OclLink, args):
    import os
    if args.dump_dir:
        os.makedirs(args.dump_dir, exist_ok=True)
    print(f"[vision] subscribed to {link.vision_endpoint}  (Ctrl-C to stop)")
    count, dumped, start, last_print = 0, 0, time.monotonic(), 0.0
    deadline = start + args.seconds if args.seconds else None
    try:
        while deadline is None or time.monotonic() < deadline:
            f = link.read_vision(timeout=1.0)
            if f is None:
                print("\n[vision] no frame in 1s — launched with -Docl.vision=true and in a world?")
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


def _cmd_drive(link: OclLink, args):
    print(f"[drive] bound OCLI PUB on {link.instruct_endpoint}")
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


def _drive_demo(link: OclLink):
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


def _cmd_roundtrip(link: OclLink, args):
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


def _cmd_all(link: OclLink, args):
    tel = link._telemetry_socket()
    vis = link._vision_socket()
    poller = zmq.Poller()
    poller.register(tel, zmq.POLLIN)
    poller.register(vis, zmq.POLLIN)

    print(f"[all] telemetry={link.telemetry_endpoint}  vision={link.vision_endpoint}  for {args.seconds}s")
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
        print("[all] NOTE: no vision frames — launch the client with -Docl.vision=true")
    return 0 if n_tel > 0 else 1


def _cmd_pointcloud(link: OclLink, args):
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


def _live_pointcloud(link: OclLink, args):
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

    sock = link._vision_socket()
    poller = zmq.Poller()
    poller.register(sock, zmq.POLLIN)

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
            if poller.poll(0):
                f = decode_vision(sock.recv())
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


def _fallback_save(link: OclLink, args):
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
        description="Open Crafter Link client library / test controller",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--telemetry", default=DEFAULT_TELEMETRY, help="mod OCLO PUB endpoint")
    p.add_argument("--vision", default=DEFAULT_VISION, help="mod OCLV PUB endpoint")
    p.add_argument("--instruct", default=DEFAULT_INSTRUCT, help="our OCLI PUB bind endpoint")
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
    link = OclLink(telemetry=args.telemetry, vision=args.vision, instruct=args.instruct)
    try:
        return args.func(link, args)
    except ValueError as e:
        print(f"[error] decode failed: {e}", file=sys.stderr)
        return 2
    finally:
        link.close()


if __name__ == "__main__":
    sys.exit(main())
