"""
Open Crafter Link — Python client library.

`pip install ./pylib` and you have the full Open Crafter Link API: read player
telemetry, read the RGBD vision stream, and drive the player.

    from ocl import Ocl

    with Ocl() as link:
        t = link.read_telemetry()                 # newest player state
        print(t.yaw, t.pitch, t.slot, t.health, t.food, t.xp_level)

        link.drive(forward=True, sprint=True)     # one instruction (hold from your loop)
        link.look(yaw=90, pitch=0)                # absolute rotation
        link.set_slot(3)                          # select hotbar slot

        f = link.read_vision()                    # newest RGBD frame
        print(f.w, f.h, f.center_depth_blocks())

Both transports are pure stdlib — no third-party dependencies. `numpy`/`pillow` are used lazily
and only by the optional helpers (`VisionFrame.to_numpy`, `frame_to_png`, `frame_to_pointcloud`).

The package also ships a CLI controller; run `ocl --help` (see `ocl.cli`).

Wire roles (from the mod's LinkConfig / BinaryCodec) — the same length-prefixed client/server
framing for both transports:

    mod  server  *:5557           "OCLO"  telemetry    -> we connect
    mod  server  *:5559           "OCLV"  RGBD vision   -> we connect
    mod  client  localhost:5558   "OCLI"  instructions  <- we BIND a server; the mod connects

Every stream conflates to the newest message (queue depth 1): the reader keeps only the freshest
frame, and each sender slot holds only the latest payload.

Transports
----------
Two wire transports are supported; pick with ``transport=`` or let it auto-detect from the
endpoints (default UDS, matching the mod):

* ``"uds"`` (default) — plain ``AF_UNIX`` domain sockets with a ``u32-LE length + payload``
  framing. Faster, same-machine only. Endpoints are filesystem paths (or ``unix:/path`` /
  ``ipc:///path``). The mod is the *server* for telemetry+vision (we connect), and we are the
  *server* for instructions (the mod connects).
* ``"tcp"`` — the identical framing over ``AF_INET``; works across a network. Selected
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
from typing import Iterator, Optional, Sequence


__version__ = "2.0.0"

__all__ = [
    "Ocl",
    "Telemetry",
    "VisionFrame",
    "Instruction",
    "Slot",
    "SlotGroupData",
    "Inventory",
    "decode_telemetry",
    "decode_vision",
    "encode_instruction",
    "frame_to_png",
    "read_depth_zip",
    "depth_zip_to_blocks",
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
DEFAULT_TELEMETRY = "tcp://localhost:5557"   # connect to the mod's telemetry server
DEFAULT_VISION = "tcp://localhost:5559"      # connect to the mod's vision server
DEFAULT_INSTRUCT = "tcp://*:5558"            # bind our instruction server (the mod connects here)

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


def _tcp_addr(endpoint: str, default_port: int) -> "tuple":
    """Parse a ``tcp://host:port`` endpoint into a ``(host, port)`` tuple for ``socket``.

    ``*`` / empty host means "all interfaces" (bind), rendered as ``""`` which ``socket.bind``
    treats as ``INADDR_ANY``. A missing port falls back to ``default_port``. Accepts a bare
    ``host:port`` or ``host`` too.
    """
    ep = (endpoint or "").strip()
    if ep.startswith("tcp://"):
        ep = ep[len("tcp://"):]
    ep = ep.split("/", 1)[0]  # strip any trailing path
    if ":" in ep:
        host, _, port_s = ep.rpartition(":")
        port = int(port_s) if port_s else default_port
    else:
        host, port = ep, default_port
    if host in ("*", ""):
        host = ""  # INADDR_ANY for bind
    return (host, port)


# --------------------------------------------------------------------------- #
# Wire format                                                                  #
# --------------------------------------------------------------------------- #
MAGIC_OUT = b"OCLO"
MAGIC_IN = b"OCLI"
MAGIC_VIS = b"OCLV"

# Versions, movement/action bitmasks, and slot-group / inventory-op opcodes are the single source of
# truth in protocol/protocol.json, generated into _protocol.py (and the mod's Protocol.java) so the two
# languages can never silently disagree. VERSION was bumped to 2 when inventory was added to OCLO/OCLI.
from ._protocol import (  # noqa: E402  (generated constants; re-exported for callers)
    VERSION, VIS_VERSION,
    M_FRONT, M_BACK, M_LEFT, M_RIGHT, M_JUMP, M_SPRINT, M_SNEAK,
    A_ATTACK, A_INTERACT,
    G_HOTBAR, G_OFFHAND, G_ARMOR, G_INVENTORY, G_CURSOR, G_DISCARD, G_EXTENSION,
    OP_NONE, OP_MOVE, OP_PICK, OP_PUT, OP_SWAP, OP_DROP, OP_DISTRIBUTE, OP_COLLECT,
)

# Canonical names for the fixed (non-extension) groups; extension carries its own registry id on the wire.
GROUP_NAMES = {
    G_HOTBAR: "hotbar",
    G_OFFHAND: "offhand",
    G_ARMOR: "armor",
    G_INVENTORY: "inventory",
    G_CURSOR: "cursor",
    G_DISCARD: "discard",
}
# Reverse lookup for addressing by name (extension groups are addressed by their registry-id name).
GROUP_IDS = {v: k for k, v in GROUP_NAMES.items()}

NAN = float("nan")


# --------------------------------------------------------------------------- #
# Messages                                                                     #
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Slot:
    """One slot in an inventory group. `item` is a Minecraft item id ("minecraft:xxx") or None when
    empty; `count` is 0 for an empty slot; `enabled` is False only for a toggled-off auto-crafter slot."""

    item: Optional[str]
    count: int
    enabled: bool

    @property
    def empty(self) -> bool:
        return self.item is None


@dataclass(frozen=True)
class SlotGroupData:
    """One slot group of the current screen. `group` is a G_* opcode; `name` is the canonical group
    name for fixed groups, or the container registry id (e.g. "minecraft:generic_9x3") for extension."""

    group: int
    name: str
    slots: tuple  # tuple[Slot, ...], index i = group-local index i

    def __str__(self) -> str:
        shown = ", ".join(
            f"{i}:{s.item.split(':')[-1]}x{s.count}{'' if s.enabled else '(off)'}"
            for i, s in enumerate(self.slots) if not s.empty
        )
        return f"{self.name}[{len(self.slots)}]: {shown}" if shown else f"{self.name}[{len(self.slots)}]: (empty)"


@dataclass(frozen=True)
class Inventory:
    """Decoded inventory section of an OCLO message: the groups visible in the current screen."""

    groups: tuple  # tuple[SlotGroupData, ...]

    def group(self, group_id: int) -> "Optional[SlotGroupData]":
        """First group with the given G_* opcode (there is at most one per opcode)."""
        for g in self.groups:
            if g.group == group_id:
                return g
        return None

    def by_name(self, name: str) -> "Optional[SlotGroupData]":
        """Group by its wire name (fixed group name like 'hotbar', or an extension registry id)."""
        for g in self.groups:
            if g.name == name:
                return g
        return None


@dataclass(frozen=True)
class Telemetry:
    """Decoded OCLO message: the player's current state, plus the current screen's inventory."""

    yaw: float
    pitch: float
    slot: int
    health: float
    food: int
    xp_level: int
    inventory: Optional[Inventory] = None

    def __str__(self) -> str:
        return (
            f"yaw={self.yaw:8.2f}  pitch={self.pitch:7.2f}  slot={self.slot}  "
            f"health={self.health:5.1f}  food={self.food}  xp={self.xp_level}"
        )


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
    """An OCLI control message: movement plus an optional discrete inventory action. Build with kwargs,
    send via `Ocl.send`. `slot_a`/`slot_b` are (group_id, index) tuples used by the inventory action."""

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
    inv_op: int = OP_NONE                       # inventory action opcode (OP_*)
    slot_a: Optional[tuple] = None              # (group_id, index) for the action's primary operand
    slot_b: Optional[tuple] = None              # (group_id, index) for SWAP's second operand
    slot_list: Optional[tuple] = None           # ((group_id, index), ...) for DISTRIBUTE targets

    def encode(self) -> bytes:
        return encode_instruction(
            front=self.front, back=self.back, left=self.left, right=self.right,
            jump=self.jump, sprint=self.sprint, sneak=self.sneak,
            slot=self.slot, attack=self.attack, interact=self.interact,
            yaw=self.yaw, pitch=self.pitch,
            inv_op=self.inv_op, slot_a=self.slot_a, slot_b=self.slot_b, slot_list=self.slot_list,
        )


# --------------------------------------------------------------------------- #
# Codec                                                                        #
# --------------------------------------------------------------------------- #
def decode_telemetry(buf: bytes) -> Telemetry:
    # magic[4] ver[1] yaw[f] pitch[f] slot[i] health[f] food[i] xpLevel[i]  then the inventory section.
    if len(buf) < 5 + 4 + 4 + 4 + 4 + 4 + 4:
        raise ValueError(f"OCLO too short: {len(buf)} bytes")
    if buf[:4] != MAGIC_OUT:
        raise ValueError(f"bad OCLO magic: {buf[:4]!r}")
    if buf[4] != VERSION:
        raise ValueError(f"bad OCLO version: {buf[4]}")
    yaw, pitch, slot, health, food, xp_level = struct.unpack_from("<ffifii", buf, 5)
    inventory = _decode_inventory(buf, 5 + 24)  # offset past the fixed 24-byte control block
    return Telemetry(yaw, pitch, slot, health, food, xp_level, inventory)


def _decode_inventory(buf: bytes, off: int) -> "Optional[Inventory]":
    """Parse the OCLO inventory section starting at `off` (see BinaryCodec's OCLO layout)."""
    if off >= len(buf):
        return None  # no inventory section present
    (group_count,) = struct.unpack_from("<B", buf, off)
    off += 1
    groups = []
    for _ in range(group_count):
        (opcode,) = struct.unpack_from("<B", buf, off); off += 1
        (name_len,) = struct.unpack_from("<H", buf, off); off += 2
        name = buf[off:off + name_len].decode("utf-8"); off += name_len
        (slot_count,) = struct.unpack_from("<H", buf, off); off += 2
        slots = []
        for _ in range(slot_count):
            (item_len,) = struct.unpack_from("<H", buf, off); off += 2
            item = buf[off:off + item_len].decode("utf-8") if item_len else None
            off += item_len
            (count, flags) = struct.unpack_from("<hB", buf, off); off += 3
            slots.append(Slot(item, count, bool(flags & 1)))
        display = name if name else GROUP_NAMES.get(opcode, f"group{opcode}")
        groups.append(SlotGroupData(opcode, display, tuple(slots)))
    return Inventory(tuple(groups))


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


def _addr(group, index: int) -> tuple:
    """Resolve a slot address to (group_opcode, index) for the wire.

    `group` may be a G_* int, a fixed-group name ('hotbar', 'discard', ...), or an extension registry id
    ('minecraft:generic_9x3', 'generic_9x3', 'anvil', ...). Any name that isn't a fixed group maps to
    G_EXTENSION — the mod resolves the extension group by position, not by name."""
    if isinstance(group, int):
        return (group, index)
    name = str(group)
    if name in GROUP_IDS:
        return (GROUP_IDS[name], index)
    return (G_EXTENSION, index)  # a container registry id -> the extension group


def encode_instruction(
    *,
    front=False, back=False, left=False, right=False,
    jump=False, sprint=False, sneak=False,
    slot=-1, attack=False, interact=False,
    yaw=NAN, pitch=NAN,
    inv_op=OP_NONE, slot_a=None, slot_b=None, slot_list=None,
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
    a_group, a_index = slot_a if slot_a is not None else (0, 0)
    b_group, b_index = slot_b if slot_b is not None else (0, 0)
    # magic[4] ver[1] move[B] slot[i] action[B] yaw[f] pitch[f]  invOp[B] aGroup[B] aIndex[h] bGroup[B] bIndex[h]
    out = MAGIC_IN + struct.pack(
        "<BBiBff" "BBhBh",
        VERSION, move, slot, action, yaw, pitch,
        inv_op, a_group, a_index, b_group, b_index,
    )
    # DISTRIBUTE appends a variable slot list: slotCount[B] then per slot group[B] index[h].
    if inv_op == OP_DISTRIBUTE:
        targets = slot_list or ()
        out += struct.pack("<B", len(targets))
        for group_id, index in targets:
            out += struct.pack("<Bh", group_id, index)
    return out


# --------------------------------------------------------------------------- #
# UDS transport helpers                                                        #
# --------------------------------------------------------------------------- #
def _read_exactly(conn: socket.socket, sel: "selectors.BaseSelector", n: int) -> Optional[bytes]:
    """Read exactly n bytes, waiting for the rest of the frame if it hasn't all arrived yet.

    The socket is non-blocking, so a large frame (e.g. a multi-MB RGBD frame that spans several
    kernel buffers) is usually only partially available on the first read. Rather than fail, we
    ``select`` for more data between reads and keep going until we have the whole ``n`` bytes.
    Returns None only on a clean EOF (peer closed mid-frame).
    """
    chunks = []
    got = 0
    while got < n:
        try:
            chunk = conn.recv(n - got)
        except BlockingIOError:
            sel.select()  # wait (blocking) for the next slice of this frame
            continue
        if not chunk:
            return None  # peer closed
        chunks.append(chunk)
        got += len(chunk)
    return b"".join(chunks)


def _read_frame(conn: socket.socket, sel: "selectors.BaseSelector") -> Optional[bytes]:
    """Read one `u32-LE length + payload` frame to completion; None on EOF."""
    head = _read_exactly(conn, sel, 4)
    if head is None:
        return None
    (length,) = _LEN.unpack(head)
    if length > _MAX_FRAME:
        raise ValueError(f"framing desync: implausible length {length}")
    return _read_exactly(conn, sel, length)


def _write_frame(conn: socket.socket, payload: bytes) -> None:
    conn.sendall(_LEN.pack(len(payload)) + payload)


class _FramedReader:
    """Client end of a mod-server stream (telemetry / vision): connect, read framed messages, and
    conflate to the newest so ``recv(timeout)`` keeps only the freshest frame (the newest-wins
    behaviour the mod's conflating slots provide).

    Transport-generic: ``family`` is ``AF_UNIX`` (``address`` = a ``.sock`` path) or ``AF_INET``
    (``address`` = a ``(host, port)`` tuple). Connection is lazy and self-healing: if the mod isn't
    up (or drops), each ``recv`` retries the connect within the timeout and returns None if nothing
    arrives.
    """

    def __init__(self, family: int, address):
        self.family = family
        self.address = address
        self._conn: Optional[socket.socket] = None
        self._sel = selectors.DefaultSelector()

    def _ensure_conn(self, deadline: Optional[float]) -> Optional[socket.socket]:
        if self._conn is not None:
            return self._conn
        while deadline is None or time.monotonic() < deadline:
            try:
                c = socket.socket(self.family, socket.SOCK_STREAM)
                c.connect(self.address)
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
        # We block (up to the deadline) for the *first* frame, then peek non-blocking for any
        # already-buffered newer frames. Each frame, once it starts arriving, is read to
        # completion (see `_read_exactly`) so a large multi-buffer frame is never truncated.
        newest = None
        while True:
            if newest is None:
                remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
                if not self._sel.select(remaining):
                    break  # nothing within the timeout
            else:
                # Already have a frame; is another whole one waiting? Peek without blocking.
                if not self._sel.select(0.0):
                    break
            try:
                frame = _read_frame(conn, self._sel)
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


class _FramedServer:
    """Server end of the instruction stream: bind, accept the mod's connection, and write framed
    instructions. Mirrors the mod's OCLI role (the mod connects to us).

    Transport-generic: ``family`` is ``AF_UNIX`` (``address`` = a ``.sock`` path, unlinked first and
    on close) or ``AF_INET`` (``address`` = a ``(host, port)`` tuple, bound with ``SO_REUSEADDR``).
    Non-blocking accept so ``send`` never stalls when the mod isn't connected yet; instructions sent
    before a connection exists are simply dropped (no consumer yet)."""

    def __init__(self, family: int, address):
        self.family = family
        self.address = address
        if family == socket.AF_UNIX:
            try:
                os.unlink(address)
            except FileNotFoundError:
                pass
        self._srv = socket.socket(family, socket.SOCK_STREAM)
        if family == socket.AF_INET:
            self._srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._srv.bind(address)
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
            return  # no consumer yet — drop
        try:
            _write_frame(self._conn, data)
        except OSError:
            self._conn.close()
            self._conn = None

    def close(self):
        if self._conn is not None:
            self._conn.close()
        self._srv.close()
        if self.family == socket.AF_UNIX:
            try:
                os.unlink(self.address)
            except FileNotFoundError:
                pass


# --------------------------------------------------------------------------- #
# Client                                                                       #
# --------------------------------------------------------------------------- #
class Ocl:
    """High-level client for the Open Crafter Link.

    Defaults to the UDS transport (matching the mod). Both transports are pure stdlib — a plain
    ``Ocl()`` (UDS) and ``Ocl(transport="tcp")`` (TCP, for a networked controller) each need no
    third-party dependencies. TCP speaks the same length-prefixed framing as UDS, just over
    ``AF_INET`` instead of ``AF_UNIX``.

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
        if transport not in ("uds", "tcp"):
            raise ValueError(f"unknown transport {transport!r} (expected 'tcp' or 'uds')")
        self.transport = transport
        self.uds_dir = uds_dir

        self._tel_sock = None
        self._vis_sock = None
        self._pub_sock = None
        self._pub_warmed = False

    # ---- socket factories -------------------------------------------------- #
    def _reader(self, endpoint, uds_name, default_port):
        """A conflating framed reader for a mod-server stream, for the active transport."""
        if self.transport == "uds":
            return _FramedReader(socket.AF_UNIX, _uds_path(endpoint, uds_name, self.uds_dir))
        return _FramedReader(socket.AF_INET, _tcp_addr(endpoint, default_port))

    def _telemetry_socket(self):
        if self._tel_sock is None:
            self._tel_sock = self._reader(self.telemetry_endpoint, UDS_TELEMETRY, 5557)
        return self._tel_sock

    def _vision_socket(self):
        if self._vis_sock is None:
            self._vis_sock = self._reader(self.vision_endpoint, UDS_VISION, 5559)
        return self._vis_sock

    def _instruct_socket(self):
        if self._pub_sock is None:
            if self.transport == "uds":
                self._pub_sock = _FramedServer(
                    socket.AF_UNIX, _uds_path(self.instruct_endpoint, UDS_INSTRUCTION, self.uds_dir))
            else:
                self._pub_sock = _FramedServer(socket.AF_INET, _tcp_addr(self.instruct_endpoint, 5558))
        return self._pub_sock

    # ---- reading ----------------------------------------------------------- #
    def read_telemetry(self, timeout: float = 1.0) -> Optional[Telemetry]:
        """Return the newest player telemetry, or None if none arrived in `timeout` seconds."""
        buf = self._recv(self._telemetry_socket(), timeout)
        return decode_telemetry(buf) if buf is not None else None

    def read_vision(self, timeout: float = 1.0) -> Optional[VisionFrame]:
        """Return the newest RGBD frame, or None within `timeout`.

        Requires the client to be in a world (vision only flows while a world renders).
        """
        buf = self._recv(self._vision_socket(), timeout)
        return decode_vision(buf) if buf is not None else None

    def _recv(self, sock, timeout: float):
        return sock.recv(timeout)  # the framed reader already conflates to the newest frame

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
            # We're the instruction server; give the mod a moment to connect to us before the first
            # send, so the very first instruction isn't silently dropped (same for TCP and UDS).
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

    # ---- inventory --------------------------------------------------------- #
    def read_inventory(self, timeout: float = 1.0) -> Optional[Inventory]:
        """Return the current screen's inventory (newest telemetry's inventory), or None."""
        t = self.read_telemetry(timeout)
        return t.inventory if t is not None else None

    def move(self, group, index: int = 0) -> None:
        """Quick-move (shift-click) the slot at (group, index). No-op on the discard slot."""
        self.drive(inv_op=OP_MOVE, slot_a=_addr(group, index))

    def pick(self, group, index: int = 0) -> None:
        """Left-click the slot. On the discard slot, throws the whole cursor stack out of the GUI."""
        self.drive(inv_op=OP_PICK, slot_a=_addr(group, index))

    def put(self, group, index: int = 0) -> None:
        """Right-click the slot. On the discard slot, throws a single item from the cursor."""
        self.drive(inv_op=OP_PUT, slot_a=_addr(group, index))

    def swap(self, group_a, index_a: int, group_b, index_b: int) -> None:
        """Swap two slots via a number-key press. Both must be non-cursor/non-discard and at least one
        must be a hotbar slot, else the mod treats it as a no-op."""
        self.drive(inv_op=OP_SWAP, slot_a=_addr(group_a, index_a), slot_b=_addr(group_b, index_b))

    def distribute(self, slots) -> None:
        """Distribute the cursor stack evenly across `slots` — the vanilla left-click drag, applied in a
        single tick (no per-slot dragging). Requires a stack held on the cursor first (e.g. via `pick`).

        `slots` is an iterable of (group, index) pairs, where group is a name ('hotbar', 'inventory',
        a container id, ...) or a G_* opcode. Example: link.distribute([("inventory", 0), ("inventory", 1)])."""
        targets = tuple(_addr(g, i) for g, i in slots)
        self.drive(inv_op=OP_DISTRIBUTE, slot_list=targets)

    def collect(self, group, index: int = 0) -> None:
        """Gather all matching items onto the cursor — the sweep of a vanilla double-click on (group, index).
        Emitted as the PICKUP_ALL click alone, mirroring the double-click's second click: it assumes the
        matching stack is already on the cursor (call `pick` on the slot first), and ends with the cursor
        holding as much of that item as it can from the open inventory."""
        self.drive(inv_op=OP_COLLECT, slot_a=_addr(group, index))

    def drop(self, group="hotbar", index: int = 0) -> None:
        """Drop one item, exactly like the vanilla drop key. With no screen open, drops one item from the
        selected hotbar stack (the (group, index) is ignored, matching vanilla). With a screen open, drops
        one item from the addressed slot."""
        self.drive(inv_op=OP_DROP, slot_a=_addr(group, index))

    def discard(self) -> None:
        """Throw the whole cursor stack out of the GUI (shorthand for pick('discard'))."""
        self.pick("discard", 0)

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
                s.close()
        self._tel_sock = self._vis_sock = self._pub_sock = None

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


def read_depth_zip(path: str) -> Iterator["np.ndarray"]:
    """Yield each frame of a recorder ``depth.png.zip`` as a ``(height, width)`` uint16 array.

    The recorder writes one 16-bit grayscale PNG per sample (``000000.png``, ``000001.png``, …),
    each pixel = ``round(distance/far * 65535)`` where ``far`` is logged per sample in
    ``actions.jsonl`` (and in ``manifest.json``). Use :func:`depth_zip_to_blocks` to convert a
    yielded array to absolute distance in blocks. Requires pillow + numpy.
    """
    import zipfile
    from PIL import Image
    import numpy as np

    with zipfile.ZipFile(path) as zf:
        names = sorted(n for n in zf.namelist() if n.endswith(".png"))
        for name in names:
            with zf.open(name) as fp:
                img = Image.open(fp)
                yield np.asarray(img, dtype=np.uint16)


def depth_zip_to_blocks(u16: "np.ndarray", far: float) -> "np.ndarray":
    """Convert a uint16 depth frame (from :func:`read_depth_zip`) to absolute distance in blocks."""
    import numpy as np

    return u16.astype(np.float32) / 65535.0 * far


# Map the recorder's lowercase op names (Java InventoryAction.Op.name().toLowerCase()) to OP_* opcodes.
_ACTION_OP_IDS = {
    "none": OP_NONE, "move": OP_MOVE, "pick": OP_PICK, "put": OP_PUT,
    "swap": OP_SWAP, "drop": OP_DROP, "distribute": OP_DISTRIBUTE, "collect": OP_COLLECT,
}


@dataclass(frozen=True)
class ActionSlot:
    """A recorded slot address: a group (name + G_* opcode) and its group-local index. `group` is None
    for a group name outside the fixed set (an extension/container id), in which case `op` is None too."""

    name: str
    op: Optional[int]
    index: int


@dataclass(frozen=True)
class InventoryEvent:
    """One recorded inventory action from an ``actions.jsonl`` sample's ``inventory`` array. `op` is an
    OP_* opcode; `op_name` its lowercase name. `a` is the primary slot (None for ops with no slot); `b`
    is the second slot, present only for ``swap`` (where `a` is the hotbar/off-hand slot and `b` the
    hovered slot); `slots` is the target list, non-empty only for ``distribute``."""

    op: int
    op_name: str
    a: Optional[ActionSlot]
    b: Optional[ActionSlot]
    slots: tuple  # tuple[ActionSlot, ...]


def _action_slot(obj) -> Optional[ActionSlot]:
    if obj is None:
        return None
    name = obj["group"]
    return ActionSlot(name=name, op=GROUP_IDS.get(name), index=int(obj["index"]))


def _inventory_event(obj) -> InventoryEvent:
    op_name = obj["op"]
    return InventoryEvent(
        op=_ACTION_OP_IDS.get(op_name, OP_NONE),
        op_name=op_name,
        a=_action_slot(obj.get("a")),
        b=_action_slot(obj.get("b")),
        slots=tuple(_action_slot(s) for s in obj.get("slots", ())),
    )


def _inventory_group(obj) -> SlotGroupData:
    """Decode one recorded 'inventory_state' group into the same SlotGroupData the live controller uses."""
    name = obj["group"]
    slots = tuple(
        Slot(item=s.get("item"), count=int(s.get("count", 0)), enabled=bool(s.get("enabled", True)))
        for s in obj.get("slots", ())
    )
    # For the fixed groups the recorder writes the canonical name; extension groups carry a registry id.
    group_op = GROUP_IDS.get(name, G_EXTENSION)
    display = name if group_op != G_EXTENSION else (obj.get("registry_id") or name)
    return SlotGroupData(group=group_op, name=display, slots=slots)


def read_actions_jsonl(path: str) -> Iterator[dict]:
    """Yield each sample of a recorder ``actions.jsonl``, one dict per line, in recorded order.

    Every scalar field (``seqno``, ``t_ns``, movement/look, ``slot``, ``near``/``far``,
    ``frame_repeated``/``frame_present``) is passed through as-is. The ``inventory`` array
    (dataset ``schema_version`` >= 4) is decoded into a list of :class:`InventoryEvent` under the
    ``inventory`` key, so each event's ``op`` lines up with the OP_* constants used by the live
    controller (``move``/``pick``/``put``/``swap``/``drop``/``distribute``/``collect``). The
    ``inventory_state`` object (``schema_version`` >= 5) — the observed screen contents that tick — is
    decoded into an :class:`Inventory` (same type the live controller returns), so you can query it with
    ``.by_name("hotbar")`` / ``.group(G_HOTBAR)``. Older files missing either field yield an empty list /
    empty :class:`Inventory`. Pure stdlib (``json``); no extra deps.
    """
    import json

    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            row["inventory"] = [_inventory_event(e) for e in row.get("inventory", ())]
            row["inventory_state"] = Inventory(
                groups=tuple(_inventory_group(g) for g in row.get("inventory_state", ()))
            )
            yield row


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

