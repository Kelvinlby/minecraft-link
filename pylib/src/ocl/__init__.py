"""
Open Crafter Link — Python client library.

`pip install ./pylib` and you have the full Open Crafter Link API: read player
telemetry, read the RGBD vision stream, and drive the player.

    from ocl import Ocl

    with Ocl() as link:
        t = link.read_telemetry()                 # newest player state
        print(t.yaw, t.pitch, t.slot)

        link.drive(forward=True, sprint=True)     # one instruction (hold from your loop)
        link.look(yaw=90, pitch=0)                # absolute rotation
        link.set_slot(3)                          # select hotbar slot

        f = link.read_vision()                    # newest RGBD frame (needs -Docl.vision=true)
        print(f.w, f.h, f.center_depth_blocks())

The default UDS transport needs no third-party dependencies. `pyzmq` is only needed for the
TCP transport (`pip install open-crafter-link[tcp]`); `numpy`/`pillow` are used lazily and
only by the optional helpers (`VisionFrame.to_numpy`, `frame_to_png`, `frame_to_pointcloud`).

The package also ships a CLI controller; run `ocl --help` (see `ocl.cli`).

Wire roles (from the mod's LinkConfig / BinaryCodec):

    mod  PUB  tcp://*:5557   "OCLO"  telemetry      -> we SUB-connect
    mod  PUB  tcp://*:5559   "OCLV"  RGBD vision     -> we SUB-connect
    mod  SUB  tcp://localhost:5558   "OCLI" instr    <- we BIND a PUB and publish

All sockets on both ends use ZMQ_CONFLATE (newest message wins, queue depth 1).

Transports
----------
Two wire transports are supported; pick with ``transport=`` or let it auto-detect from the
endpoints (default UDS, matching the mod):

* ``"uds"`` (default) — plain ``AF_UNIX`` domain sockets with a ``u32-LE length + payload``
  framing (no ZeroMQ). Faster, same-machine only, no third-party deps. Endpoints are filesystem
  paths (or ``unix:/path`` / ``ipc:///path``). Roles mirror TCP: the mod is the *server* for
  telemetry+vision (we connect), and we are the *server* for instructions (the mod connects).
* ``"tcp"`` — ZeroMQ PUB/SUB over TCP, as above; works across a network. Needs ``pyzmq``. Selected
  automatically when you pass a custom ``tcp://`` endpoint. The payload bytes are identical to UDS.

The default UDS directory matches the mod's resolver (``$XDG_RUNTIME_DIR`` on Linux, or the
Flatpak app runtime dir inside a sandbox); override with ``--uds-dir``.
"""

from __future__ import annotations

import os
import selectors
import socket
import struct
import time
from dataclasses import dataclass
from typing import Optional, Sequence

# pyzmq is only needed for the TCP transport; imported lazily so UDS-only use has no dependency.
try:
    import zmq
except ImportError:  # pragma: no cover
    zmq = None


__version__ = "1.0.0"

__all__ = [
    "Ocl",
    "Telemetry",
    "VisionFrame",
    "Instruction",
    "decode_telemetry",
    "decode_vision",
    "encode_instruction",
    "frame_to_png",
    "frame_to_pointcloud",
    "save_ply",
    "angle_close",
    "resolve_uds_dir",
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
# UDS transport (mirror the mod's LinkConfig)                                  #
# --------------------------------------------------------------------------- #
UDS_TELEMETRY = "open-crafter-link-telemetry.sock"     # mod binds; we connect
UDS_INSTRUCTION = "open-crafter-link-instruction.sock"  # we bind; mod connects
UDS_VISION = "open-crafter-link-vision.sock"           # mod binds; we connect

# u32 little-endian length prefix that precedes every framed payload (matches UdsBridge).
_LEN = struct.Struct("<I")
_MAX_FRAME = 64 * 1024 * 1024


def resolve_uds_dir(uds_dir: Optional[str] = None) -> str:
    """Resolve the directory holding the three .sock files, matching the mod's resolver.

    Order: explicit ``uds_dir`` -> Flatpak app runtime dir (if ``$FLATPAK_ID`` set) ->
    ``$XDG_RUNTIME_DIR`` -> the system temp dir. The directory is created if missing.
    """
    if uds_dir:
        chosen = uds_dir
    else:
        xdg = os.environ.get("XDG_RUNTIME_DIR")
        flatpak_id = os.environ.get("FLATPAK_ID")
        if flatpak_id and xdg:
            chosen = os.path.join(xdg, "app", flatpak_id)
        elif xdg:
            chosen = xdg
        else:
            import tempfile
            chosen = tempfile.gettempdir()
    os.makedirs(chosen, exist_ok=True)
    return chosen


def _looks_like_uds(*endpoints: str) -> bool:
    """True if any endpoint uses a UDS scheme (unix:/ipc:/) or is a bare filesystem path."""
    for ep in endpoints:
        ep = (ep or "").strip()
        if ep.startswith(("ipc://", "unix://", "unix:")):
            return True
        if ep and not ep.startswith("tcp://") and ("/" in ep or ep.endswith(".sock")):
            return True
    return False


def _uds_path(endpoint: str, name: str, uds_dir: Optional[str]) -> str:
    """Turn a UDS endpoint into a concrete socket path.

    ``endpoint`` may be a bare path, ``unix:/path``, or ``ipc:///path``; if it still looks like
    a ``tcp://`` default (i.e. the caller only switched transport, not the endpoint), fall back
    to ``<resolve_uds_dir>/name``.
    """
    ep = (endpoint or "").strip()
    for scheme in ("ipc://", "unix://", "unix:"):
        if ep.startswith(scheme):
            ep = ep[len(scheme):]
            return ep
    if ep and not ep.startswith("tcp://"):
        return ep
    return os.path.join(resolve_uds_dir(uds_dir), name)


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
    """An OCLI control message. Build with kwargs, send via `Ocl.send`."""

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
# UDS transport helpers                                                        #
# --------------------------------------------------------------------------- #
def _read_exactly(conn: socket.socket, n: int) -> Optional[bytes]:
    """Read exactly n bytes from a blocking/selected socket; None on clean EOF."""
    chunks = []
    got = 0
    while got < n:
        chunk = conn.recv(n - got)
        if not chunk:
            return None
        chunks.append(chunk)
        got += len(chunk)
    return b"".join(chunks)


def _read_frame(conn: socket.socket) -> Optional[bytes]:
    """Read one `u32-LE length + payload` frame; None on EOF."""
    head = _read_exactly(conn, 4)
    if head is None:
        return None
    (length,) = _LEN.unpack(head)
    if length > _MAX_FRAME:
        raise ValueError(f"framing desync: implausible length {length}")
    return _read_exactly(conn, length)


def _write_frame(conn: socket.socket, payload: bytes) -> None:
    conn.sendall(_LEN.pack(len(payload)) + payload)


class _UdsReader:
    """Client end of a mod-server stream (telemetry / vision): connect, read framed messages,
    and conflate to the newest so ``recv(timeout)`` mirrors ZMQ_CONFLATE semantics.

    Connection is lazy and self-healing: if the mod isn't up (or drops), each ``recv`` retries
    the connect within the timeout and returns None if nothing arrives, exactly like the ZMQ SUB.
    """

    def __init__(self, path: str):
        self.path = path
        self._conn: Optional[socket.socket] = None
        self._sel = selectors.DefaultSelector()

    def _ensure_conn(self, deadline: Optional[float]) -> Optional[socket.socket]:
        if self._conn is not None:
            return self._conn
        while deadline is None or time.monotonic() < deadline:
            try:
                c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                c.connect(self.path)
                c.setblocking(False)
                self._sel.register(c, selectors.EVENT_READ)
                self._conn = c
                return c
            except (FileNotFoundError, ConnectionRefusedError, OSError):
                time.sleep(0.05)
        return None

    def recv(self, timeout: Optional[float]) -> Optional[bytes]:
        deadline = None if timeout is None else time.monotonic() + timeout
        conn = self._ensure_conn(deadline)
        if conn is None:
            return None
        # Drain everything currently readable, keep only the newest frame (conflate).
        newest = None
        first = True
        while True:
            remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
            # After we have one frame, don't block for more — just grab what's already buffered.
            wait = remaining if (first and newest is None) else 0.0
            if not self._sel.select(wait):
                break
            first = False
            try:
                frame = _read_frame(conn)
            except (OSError, ValueError):
                frame = None
            if frame is None:  # peer closed — drop and let the next recv reconnect
                self._reset()
                break
            newest = frame
        return newest

    def _reset(self):
        if self._conn is not None:
            try:
                self._sel.unregister(self._conn)
            except KeyError:
                pass
            self._conn.close()
            self._conn = None

    def close(self):
        self._reset()
        self._sel.close()


class _UdsInstructionServer:
    """Server end of the instruction stream: bind the .sock, accept the mod's connection, and
    write framed instructions. Mirrors the mod's OCLI role (the mod connects to us).

    Non-blocking accept so ``send`` never stalls when the mod isn't connected yet; instructions
    sent before a connection exists are simply dropped (matching PUB with no subscriber)."""

    def __init__(self, path: str):
        self.path = path
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
        self._srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._srv.bind(path)
        self._srv.listen(1)
        self._srv.setblocking(False)
        self._conn: Optional[socket.socket] = None

    def _accept_if_pending(self):
        try:
            conn, _ = self._srv.accept()
            if self._conn is not None:
                self._conn.close()  # newest connection wins
            conn.setblocking(True)
            self._conn = conn
        except (BlockingIOError, OSError):
            pass

    def send(self, data: bytes) -> None:
        self._accept_if_pending()
        if self._conn is None:
            return  # no consumer yet — drop, like PUB with no subscriber
        try:
            _write_frame(self._conn, data)
        except OSError:
            self._conn.close()
            self._conn = None

    def close(self):
        if self._conn is not None:
            self._conn.close()
        self._srv.close()
        try:
            os.unlink(self.path)
        except FileNotFoundError:
            pass


# --------------------------------------------------------------------------- #
# Client                                                                       #
# --------------------------------------------------------------------------- #
class Ocl:
    """High-level client for the Open Crafter Link.

    Defaults to the UDS transport (matching the mod), so a plain ``Ocl()`` needs no
    third-party dependencies. Pass ``transport="tcp"`` (or a custom ``tcp://`` endpoint)
    for a networked controller — that path needs ``pyzmq`` (``pip install open-crafter-link[tcp]``).

    Lazily creates the sockets the first time each stream is used, so you only
    pay for what you touch. Use as a context manager (or call `close()`) to
    release them.

        with Ocl() as link:                 # UDS by default
            print(link.read_telemetry())
            link.look(90, 0)
    """

    def __init__(
        self,
        telemetry: str = DEFAULT_TELEMETRY,
        vision: str = DEFAULT_VISION,
        instruct: str = DEFAULT_INSTRUCT,
        *,
        transport: Optional[str] = None,
        uds_dir: Optional[str] = None,
        context: "Optional[zmq.Context]" = None,
    ):
        self.telemetry_endpoint = telemetry
        self.vision_endpoint = vision
        self.instruct_endpoint = instruct
        # Transport selection when not stated (mirrors the mod, whose default is UDS):
        #   * any UDS-looking endpoint (a path / unix:/ipc://)         -> UDS
        #   * a tcp:// endpoint the caller *customized* off the default -> TCP (networked controller)
        #   * otherwise (untouched defaults)                            -> UDS, the default
        if transport is None:
            if _looks_like_uds(telemetry, vision, instruct):
                transport = "uds"
            elif (telemetry, vision, instruct) != (DEFAULT_TELEMETRY, DEFAULT_VISION, DEFAULT_INSTRUCT):
                transport = "tcp"
            else:
                transport = "uds"
        self.transport = transport
        self.uds_dir = uds_dir

        self._owns_ctx = False
        self._ctx = None
        self._tel_sock = None
        self._vis_sock = None
        self._pub_sock = None
        self._pub_warmed = False

        if transport == "tcp":
            if zmq is None:
                raise ImportError(
                    "pyzmq is required for the TCP transport (UDS is the default and needs none): "
                    "pip install 'open-crafter-link[tcp]'")
            self._owns_ctx = context is None
            self._ctx = context or zmq.Context.instance()
        elif transport != "uds":
            raise ValueError(f"unknown transport {transport!r} (expected 'tcp' or 'uds')")

    # ---- socket factories -------------------------------------------------- #
    def _sub(self, endpoint):
        s = self._ctx.socket(zmq.SUB)
        s.setsockopt(zmq.CONFLATE, 1)     # match the mod: newest wins
        s.setsockopt(zmq.SUBSCRIBE, b"")  # CONFLATE requires subscribe-all
        s.connect(endpoint)
        return s

    def _telemetry_socket(self):
        if self._tel_sock is None:
            if self.transport == "uds":
                self._tel_sock = _UdsReader(_uds_path(self.telemetry_endpoint, UDS_TELEMETRY, self.uds_dir))
            else:
                self._tel_sock = self._sub(self.telemetry_endpoint)
        return self._tel_sock

    def _vision_socket(self):
        if self._vis_sock is None:
            if self.transport == "uds":
                self._vis_sock = _UdsReader(_uds_path(self.vision_endpoint, UDS_VISION, self.uds_dir))
            else:
                self._vis_sock = self._sub(self.vision_endpoint)
        return self._vis_sock

    def _instruct_socket(self):
        if self._pub_sock is None:
            if self.transport == "uds":
                self._pub_sock = _UdsInstructionServer(
                    _uds_path(self.instruct_endpoint, UDS_INSTRUCTION, self.uds_dir))
            else:
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
        if self.transport == "uds":
            return sock.recv(timeout)  # _UdsReader already conflates to the newest frame
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
            # TCP PUB/SUB needs a moment for the mod's SUB to (re)connect before the first
            # message lands; UDS instead needs a moment for the mod to connect to our server.
            # Either way, a short warm-up avoids silently dropping the very first instruction.
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

    # ---- describe (for CLI banners) --------------------------------------- #
    def describe_telemetry(self) -> str:
        return self._describe(self.telemetry_endpoint, UDS_TELEMETRY)

    def describe_vision(self) -> str:
        return self._describe(self.vision_endpoint, UDS_VISION)

    def describe_instruct(self) -> str:
        return self._describe(self.instruct_endpoint, UDS_INSTRUCTION)

    def _describe(self, endpoint: str, name: str) -> str:
        if self.transport == "uds":
            return f"uds:{_uds_path(endpoint, name, self.uds_dir)}"
        return endpoint

    # ---- lifecycle --------------------------------------------------------- #
    def close(self) -> None:
        for s in (self._tel_sock, self._vis_sock, self._pub_sock):
            if s is not None:
                if self.transport == "uds":
                    s.close()
                else:
                    s.close(0)  # ZMQ: 0 = drop pending, don't linger
        self._tel_sock = self._vis_sock = self._pub_sock = None
        if self._owns_ctx and zmq is not None and self._ctx is not zmq.Context.instance():
            self._ctx.term()

    def __enter__(self) -> "Ocl":
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

