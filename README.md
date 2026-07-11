# Open Crafter Link for Minecraft

[![build](https://img.shields.io/github/actions/workflow/status/Kelvinlby/minecraft-link/build.yml?branch=main&style=for-the-badge&logo=github&label=Build)](https://github.com/Kelvinlby/minecraft-link/actions/workflows/build.yml)

A client-side **Fabric** mod that bridges a running Minecraft client to an external
**Open Crafter** controller over a small custom binary protocol on raw sockets. It streams the
player's state and a real RGBD view of the world *out*, and applies movement / look / action
instructions *in* — turning a vanilla client into a controllable embodied environment. It can
also **record** aligned RGBD-frame + player-action datasets straight from human play.

- **Minecraft:** 1.21.11 · **Loader:** Fabric · **Side:** client only
- **Transport:** Unix domain sockets (`AF_UNIX`) by default — a faster local-only link — or
  plain TCP (`AF_INET`) for a networked controller; selectable in the settings screen
  (**Link → Transport**). Both use the same `u32-LE length + payload` framing with no
  third-party dependency; each stream conflates to the newest message (queue depth 1)

## How it works

The mod runs a `TcpBridge` or `UdsBridge` (both extending `AbstractLinkBridge`) with three
independent streams. Each socket lives on its own worker thread; the game's tick and render
threads only ever hand off through lock-free single-slot `AtomicReference`s, so neither
game-critical thread blocks on I/O.

| Stream | Magic | Direction | Mod socket | Cadence |
|--------|-------|-----------|------------|---------|
| Telemetry    | `OCLO` | MC → controller | server bind `*:5557` | ~20 Hz (client tick) |
| Vision (RGBD)| `OCLV` | MC → controller | server bind `*:5559` | up to `visionMaxHz` (render thread) |
| Instructions | `OCLI` | controller → MC | client connect `<host>:5558` | applied per tick |

The mod **binds** its two outbound servers (stable, long-lived) and **connects** its inbound
instruction stream to the controller's server. The UDS transport mirrors the same bind/connect
split over `AF_UNIX` socket files. The controller host comes from the configured TCP URL; ports
are canonical. See `BinaryCodec` for the exact little-endian wire layouts, and
[`protocol/README.md`](protocol/README.md) for how the two codecs are kept in sync.

### Control (inbound, `OCLI`)
Each instruction lives for exactly one tick: `TickDriver` applies it and clears the slot,
so a lagging controller never repeats a stale command (movement releases, rotation/slot
hold). Supported: 7 movement flags (forward/back/left/right/jump/sprint/sneak), absolute
yaw/pitch (pitch clamped to ±90°), hotbar slot select, and attack/use clicks. Clicks are
routed through Minecraft's own `doAttack`/`doItemUse` via an `@Invoker` mixin, so they
respect cooldowns and emit the same packets as a real mouse press.

An OCLI frame also carries an optional **inventory action** — `move` (quick-move / shift-click),
`pick` (left-click), `put` (right-click), `swap` (number-key swap), `drop` (the vanilla drop
key: with no screen open it drops one item from the selected hotbar stack; with a screen open it
drops one item from the addressed slot), `distribute` (a left-click drag that splits the cursor
stack evenly across a list of slots, emitted as the vanilla 3-stage `QUICK_CRAFT` sequence in a
single tick), or `collect` (a double-click that gathers all matching stacks onto the cursor, emitted
as `PICKUP` + `PICKUP_ALL`) — targeting slots by a stable `(group, index)` address (see Telemetry
below). Because movement is a held level that
conflates (newest wins) while an action is a discrete edge event, the bridge routes actions to a
separate non-conflating FIFO queue so none is lost even while movement streams at ~30 Hz; the mod
executes each via `interactionManager.clickSlot`.

### Telemetry (outbound, `OCLO`)
Per-tick player state: yaw, pitch, selected hotbar slot, health, food, XP level — followed by the
current screen's **inventory**, normalized into stable groups (`hotbar` 0–8, `offhand` 0, `armor`
0–3 head→feet, `inventory` 0–26, `cursor` 0, virtual `discard` 0, and an `extension` group named by
the container's registry id, e.g. `minecraft:generic_9x3` / `minecraft:anvil` / `minecraft:crafting`).
Each slot reports its item id (or empty), count, and enabled flag (auto-crafter slots can be
toggled off). The mapping is screen-independent, so a `(group, index)` address means the same slot
whether or not a container is open.

### Vision (outbound, `OCLV`)
Real RGBD captured on the render thread at the `WorldRenderEvents.END_MAIN` seam — after
the 3D world and first-person hand, but **before the HUD** — so frames are the pure scene
with no overlay. The GPU→CPU readback is asynchronous through a triple-buffered ring;
frames are downsampled straight out of mapped GPU memory (nearest-neighbour, or box-filter
for RGB when enabled) and the heavy float conversion + depth linearization runs off the
render thread. Depth is linear eye-space distance normalized by the far plane (0..1), with
`near`/`far` (blocks) carried in each frame header.

## Configuration

Settings live in an in-game screen (via [Mod Menu](https://modrinth.com/mod/modmenu) +
[YACL](https://modrinth.com/mod/yacl)) and persist to
`config/open-crafter-link.json`. Changes are applied live on save — the bridge rebinds and
the vision resolution updates without a client restart.

| Setting | Default | Effect |
|---------|---------|--------|
| **Transport** | `UDS` | Link transport — `UDS` (local `AF_UNIX`) or `TCP` (plain TCP, networked) |
| **TCP URL** | `tcp://127.0.0.1` | TCP mode: controller host (host only; ports are canonical) for the inbound instruction stream |
| **UDS directory** | *(blank)* | UDS mode: directory for the `.sock` files; blank = auto-resolve |
| **Camera width**  | `768` | Width of published vision frames (px) |
| **Camera height** | `432` | Height of published vision frames (px) |

### Recording (dataset capture)

The **Recording** tab arms a dataset recorder: while enabled, every world you enter (single- or
multiplayer) is captured to its own session under `<gameDir>/open-crafter-link/<timestamp>/` and
finalized (async, with a save-progress toast) when you leave the world — menus and the title screen
are never recorded. Each session holds aligned RGBD frames + player actions sampled at
**Sample rate** Hz (default `20`, one per tick), recorded at the Sensors-tab camera resolution (the
recorder taps the link's existing vision pipeline, so there is no separate recording resolution).

RGB is encoded to `rgb.mp4` through a **system-installed FFmpeg** (configurable codec/quality/keyframe
interval and GPU-vs-CPU backend); actions and depth are still written even when no ffmpeg binary is
found.

| Setting | Default | Effect |
|---------|---------|--------|
| **Record dataset** | `false` | Arm world-scoped dataset recording |
| **Sample rate** | `20` Hz | Aligned samples per second (20 = one per tick) |
| **Encoder backend** | `AUTO` | `AUTO` (GPU→CPU), `GPU`, or `CPU` ffmpeg encoding |
| **Codec** | `H264` | Output video codec (`H264` or `H265`) |
| **Quality** | `18` | CRF/CQ (0–51, lower = better/larger) |
| **Keyframe interval** | `2` s | Seconds between keyframes |
| **FFmpeg path** | *(blank)* | Explicit ffmpeg binary; blank = search `PATH` |

### Launch-property overrides

Set via JVM args (`-Docl.<name>=<value>`); when present, these win over the in-game settings.

| Property | Default | Notes |
|----------|---------|-------|
| `ocl.visionWidth` / `ocl.visionHeight` | from settings | Pin the downsample resolution |
| `ocl.visionMaxHz` | `40` | Cap on capture rate |
| `ocl.visionBoxFilter` | `false` | Box-average RGB on downsample instead of nearest-neighbour |
| `ocl.tcpHost` | from settings | TCP mode: pin the controller host the mod connects to for instructions |
| `ocl.udsDir` | from settings | Pin the directory holding the UDS `.sock` files |
| `ocl.ffmpegPath` | from settings | Pin the ffmpeg binary used for `rgb.mp4` recording |

## Building

A standard Fabric/Loom project:

```bash
./gradlew build      # jar lands in build/libs/
./gradlew runClient  # launch a dev client with the mod loaded
```

CI builds every push (see the badge above).

## Python client

[`pylib/`](pylib/README.md) is a pip-installable client library (`pip install ./pylib`, imported as
`ocl`) exposing the full API — read telemetry, read the RGBD vision stream, and drive the
player — plus an `ocl` command-line controller that exercises every link feature
(telemetry, vision-with-PNG-dumps, drive/demo, and a closed-loop roundtrip).
See [`pylib/README.md`](pylib/README.md) for setup and usage.

## Status

The data plane — control, telemetry, and RGBD vision — is feature-complete, and the
in-game settings are wired into the live runtime.

- **Transport:** UDS (real `AF_UNIX`, the default) or TCP (`AF_INET`, for a networked controller),
  chosen in the settings screen. Both drop any ZeroMQ dependency: the link only needs conflated,
  fire-and-forget binary messages, so each transport uses a trivial `u32-LE length + payload`
  framing over Java's built-in sockets (UDS via JEP 380 `AF_UNIX`) — none of ZeroMQ's wire
  machinery (ZMTP, PUB/SUB, routing) is required. The payload bytes are identical across both
  transports; only the socket family differs. The Python controller (`pylib/`, imported as
  `ocl`) speaks both. See [`pylib/README.md`](pylib/README.md) for the UDS socket paths and the
  Flatpak-sandbox directory note.
- **Vision downsampling** maps the framebuffer onto the target resolution per-axis with no
  aspect-ratio correction; pick a camera width/height matching your window aspect to avoid
  a squashed image (the 768×432 default suits a typical 16:9 window).
