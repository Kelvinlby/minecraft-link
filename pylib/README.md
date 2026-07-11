# ocl — Python client library

`ocl` is the Python client for the [Open Crafter Link](../README.md) Minecraft mod
(distributed as the `open-crafter-link` package). Install it once and you get the full
API — read player telemetry, read the RGBD vision stream, and drive the player — plus an
`ocl` command-line controller.

```bash
pip install ./pylib
```

Both the **UDS** and **TCP** transports are pure standard library, so the base install has **no
third-party dependencies**. `numpy`, `pillow`, `open3d` and `matplotlib` are optional — install
the extras you want:

```bash
pip install "./pylib[vision]"      # PNG dumps + VisionFrame.to_numpy()
pip install "./pylib[pointcloud]"  # live 3D RGBD viewer
pip install "./pylib[all]"         # everything
```

> Per the project convention you can do this inside a `uv` environment:
> `uv venv .venv && uv pip install --python .venv "./pylib[all]"`.

## Library use

```python
from ocl import Ocl

with Ocl() as link:                       # endpoints default to LinkConfig's
    t = link.read_telemetry()                 # newest player state (or None on timeout)
    print(t.yaw, t.pitch, t.slot)

    link.look(yaw=90, pitch=0)                # absolute rotation
    link.set_slot(3)                          # select hotbar slot
    link.drive(forward=True, sprint=True)     # one instruction
    link.hold(2.0, forward=True)              # *hold* a movement (republish each tick)

    inv = link.read_inventory()               # current screen's inventory (or None)
    if inv:
        for s in inv.by_name("hotbar").slots:
            print(s.item, s.count, s.enabled)
    link.move("hotbar", 0)                     # quick-move (shift-click)
    link.pick("inventory", 5)                 # left-click; picks up onto the cursor
    link.put("generic_9x3", 2)                # right-click a chest slot (extension group)
    link.swap("hotbar", 0, "inventory", 5)    # number-key swap (one side must be hotbar)
    link.drop()                               # drop one item (vanilla drop key)
    link.discard()                            # throw the whole cursor stack out of the GUI
    link.distribute([("inventory", 0), ("inventory", 1), ("inventory", 2)])  # left-drag split
    link.collect("inventory", 0)              # double-click: gather matching items onto the cursor

    f = link.read_vision()                    # newest RGBD frame
    if f:
        print(f.w, f.h, f.center_depth_blocks())
        rgb, depth = f.to_numpy()             # (h,w,3) and (h,w) float32 (needs numpy)
```

Custom endpoints: `Ocl(telemetry="tcp://host:5557", vision=..., instruct=...)`.
Low-level codecs are exported too: `decode_telemetry`, `decode_vision`,
`encode_instruction`, plus the `Telemetry` / `VisionFrame` / `Instruction` types and the
`frame_to_png` / `frame_to_pointcloud` / `save_ply` helpers.

### Transports (TCP vs UDS)

The link speaks two wire transports; pick one in the mod's settings screen (**Link →
Transport**) and match it here:

- **UDS** (default) — plain `AF_UNIX` domain sockets with a `u32-LE length + payload` framing.
  Faster and lower-latency, but **same-machine only**.
- **TCP** — the identical framing over `AF_INET`. Works across a network; no extra deps. The
  message payloads are byte-identical to UDS; only the socket family differs.

```python
# Default is UDS — auto-resolves the socket directory the same way the mod does:
with Ocl() as link:
    print(link.read_telemetry())

# pin an explicit directory (must match what the mod uses):
Ocl(uds_dir="/run/user/1000")

# opt into TCP for a networked controller (no extra deps):
Ocl("tcp://192.168.1.5:5557", "tcp://192.168.1.5:5559", "tcp://*:5558")
```

When the transport isn't stated it defaults to UDS (matching the mod); passing a custom
`tcp://` endpoint auto-selects TCP, and a `unix:`/`ipc://`/path endpoint selects UDS. On the
CLI, use `--transport tcp` or `--uds-dir DIR` (both transports are pure stdlib — no extra deps).

**Socket directory.** In UDS mode the three `.sock` files live in a directory both processes
must agree on. Blank/auto resolves (matching the mod): `$XDG_RUNTIME_DIR`, or — inside a Flatpak
sandbox (`$FLATPAK_ID` set) — `$XDG_RUNTIME_DIR/app/$FLATPAK_ID/`, else the system temp dir. For
a **Flatpak-sandboxed** Minecraft (e.g. Prism), the host controller must write its sockets into
that same shared app-runtime dir; set `--uds-dir "$XDG_RUNTIME_DIR/app/<PrismFlatpakId>"` on the
host so both sides meet on the same files. The mod's directory can be overridden with the
`ocl.udsDir` launch property or the **UDS directory** setting.

## CLI controller

Installing the package puts an `ocl` command on your `PATH` (equivalently,
`python -m ocl`).

Vision is always on — no launch flag needed. To pin a custom resolution/rate, optionally set:

```
# optional: -Docl.visionWidth=128 -Docl.visionHeight=128 -Docl.visionMaxHz=40
#   (otherwise the resolution comes from the in-game settings screen)
```

Join a world (telemetry and vision only flow while a world is rendering), then:

```bash
# Player-state telemetry: yaw/pitch/slot + live rate (~20 Hz)
ocl telemetry

# RGBD vision: rate, center-pixel depth, and dump frames to PNG (proves NO HUD)
ocl vision --dump-dir /tmp/ocl_frames --frames 3
#   -> inspect rgb_000.png (world + first-person hand, no hotbar/crosshair)
#      and depth_000.png (grayscale; nearer = darker)

# Drive the player. Movements must be re-sent each tick to "hold", so --hold N
# republishes for N seconds. Look is absolute degrees.
ocl drive --look 90 0          # face yaw 90
ocl drive --forward --sprint --hold 2
ocl drive --slot 4 --hold 0    # single shot
ocl drive --demo               # walk through every control

# Inventory: read the current screen's slots (group by group), --all shows empties
ocl inventory
ocl inventory --all

# Inventory actions. group = hotbar/offhand/armor/inventory/discard, or a container
# registry id (generic_9x3, anvil, crafting, ...) for extension slots; index defaults to 0.
ocl move hotbar 0              # quick-move (shift-click)
ocl pick inventory 5          # left-click
ocl put generic_9x3 2         # right-click a chest slot
ocl swap hotbar 0 inventory 5 # number-key swap (one side must be hotbar)
ocl drop                      # drop one item (no screen: selected hotbar; screen: addressed slot)
ocl drop inventory 5          # in a screen, drop one item from inventory slot 5
ocl discard                   # throw the whole cursor stack out of the GUI
ocl distribute inventory 0 inventory 1 inventory 2   # left-drag: split cursor stack over these slots
ocl collect inventory 0       # double-click: gather all matching items onto the cursor

# Closed loop: send a rotation, read telemetry back, assert it actually changed
ocl roundtrip --yaw 123 --pitch -20

# Dashboard: telemetry + vision rates side by side for 10s
ocl all --seconds 10

# 3D RGBD "box": front face = the in-game image, perpendicular axis = depth
ocl pointcloud                       # live, corner view (needs the [pointcloud] extra)
ocl pointcloud --orbit-speed 1       # slowly spin to show depth
ocl pointcloud --angle 35 --elevation 25 --depth-scale 1.5
ocl pointcloud --save scene.ply      # one frame -> .ply, no GUI
```

Use `--telemetry` / `--vision` / `--instruct` (and `--transport` / `--uds-dir`) before the
subcommand to point at non-default endpoints.

## Wire roles

**TCP transport** (`AF_INET`, `u32-LE length + payload` framing):

| Stream | Magic | Mod socket            | Controller socket      |
|--------|-------|-----------------------|------------------------|
| Telemetry | `OCLO` | server bind `*:5557` | client connect `host:5557` |
| Vision    | `OCLV` | server bind `*:5559` | client connect `host:5559` |
| Instructions | `OCLI` | client connect `host:5558` | server bind `*:5558` |

Every stream conflates to the newest message (queue depth 1, newest wins).

**UDS transport** (`AF_UNIX`, `u32-LE length + payload` framing, in `<uds-dir>/`):

| Stream | Magic | Mod socket | Controller socket | Socket file |
|--------|-------|------------|-------------------|-------------|
| Telemetry | `OCLO` | server (bind + accept) | client (connect) | `open-crafter-link-telemetry.sock` |
| Vision    | `OCLV` | server (bind + accept) | client (connect) | `open-crafter-link-vision.sock` |
| Instructions | `OCLI` | client (connect) | server (bind + accept) | `open-crafter-link-instruction.sock` |

Roles mirror TCP's bind/connect split. Conflation is emulated (each side keeps only the newest
buffered frame). The payload bytes are identical to TCP — only the framing/transport differ.

## Point-cloud geometry

`pointcloud` builds an **extruded image-plane box** — it keeps each pixel on a flat front face
(the image as seen in-game) and pushes it backward by its depth:

- `X = (col / (w−1) − 0.5) · aspect`  → width, face spans ±aspect/2  (`aspect = w/h`)
- `Y = 0.5 − row / (h−1)`             → height, face spans ±0.5, +Y up (top row at top)
- `Z = −depth_norm · depth_scale`     → 0 at the near plane, −depth_scale at the far plane

So the front face (depth 0) is exactly the photo and the perpendicular axis is depth. **Every**
pixel is kept — including the far-plane sky, which forms the box's colored back wall.

The camera views the box from a corner: `--angle` (azimuth) and `--elevation` set the viewpoint,
`--orbit-speed` slowly spins it, and `--depth-scale` sets the depth-axis length (0 = auto-fit to a
roughly cubic box). The view is orthographic and refit to the box each frame, so it stays framed.

## Depth units

Depth on the wire is normalized `0..1` (= linear eye-space distance ÷ far plane).
`near`/`far` (blocks) travel in each frame's header, so the client reports both the
normalized value and the absolute distance in blocks (`norm * far`).
