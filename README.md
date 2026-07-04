# Open Crafter Link for Minecraft

[![build](https://img.shields.io/github/actions/workflow/status/Kelvinlby/minecraft-link/build.yml?branch=main&style=for-the-badge&logo=github&label=Build)](https://github.com/Kelvinlby/minecraft-link/actions/workflows/build.yml)

A client-side **Fabric** mod that bridges a running Minecraft client to an external
**Open Crafter** controller over [ZeroMQ](https://zeromq.org/). It streams the player's
state and a real RGBD view of the world *out*, and applies movement / look / action
instructions *in* — turning a vanilla client into a controllable embodied environment.

- **Minecraft:** 1.21.11 · **Loader:** Fabric · **Side:** client only
- **Transport:** Unix domain sockets (`AF_UNIX`, length-prefixed framing) by default — a
  faster local-only link with no ZeroMQ — or ZMQ over TCP (PUB/SUB, all sockets conflated —
  newest message wins) for a networked controller; selectable in the settings screen
  (**Link → Transport**)

## How it works

The mod runs a `ZmqBridge` with three independent streams. ZMQ sockets each live on
their own worker thread; the game's tick and render threads only ever hand off through
lock-free single-slot `AtomicReference`s, so neither game-critical thread blocks on I/O.

| Stream | Magic | Direction | Mod socket | Cadence |
|--------|-------|-----------|------------|---------|
| Telemetry    | `OCLO` | MC → controller | PUB bind `*:5557` | ~20 Hz (client tick) |
| Vision (RGBD)| `OCLV` | MC → controller | PUB bind `*:5559` | up to `visionMaxHz` (render thread) |
| Instructions | `OCLI` | controller → MC | SUB connect `<host>:5558` | applied per tick |

The mod **binds** its two outbound PUB sockets (stable, long-lived) and **connects** its
inbound SUB to the controller's PUB. The controller host comes from the configured TCP URL;
ports are canonical. See `BinaryCodec` for the exact little-endian wire layouts.

### Control (inbound, `OCLI`)
Each instruction lives for exactly one tick: `TickDriver` applies it and clears the slot,
so a lagging controller never repeats a stale command (movement releases, rotation/slot
hold). Supported: 7 movement flags (forward/back/left/right/jump/sprint/sneak), absolute
yaw/pitch (pitch clamped to ±90°), hotbar slot select, and attack/use clicks. Clicks are
routed through Minecraft's own `doAttack`/`doItemUse` via an `@Invoker` mixin, so they
respect cooldowns and emit the same packets as a real mouse press.

### Telemetry (outbound, `OCLO`)
Per-tick player state: yaw, pitch, selected hotbar slot.

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
| **TCP URL** | `tcp://127.0.0.1` | Controller host for the inbound instruction stream |
| **Camera width**  | `256` | Width of published vision frames (px) |
| **Camera height** | `144` | Height of published vision frames (px) |

### Launch-property overrides

Set via JVM args (`-Docl.<name>=<value>`); when present, these win over the in-game settings.

| Property | Default | Notes |
|----------|---------|-------|
| `ocl.visionWidth` / `ocl.visionHeight` | from settings | Pin the downsample resolution |
| `ocl.visionMaxHz` | `40` | Cap on capture rate |
| `ocl.visionBoxFilter` | `false` | Box-average RGB on downsample instead of nearest-neighbour |
| `ocl.pubEndpoint` / `ocl.subEndpoint` / `ocl.visPubEndpoint` | derived | Pin a full ZMQ endpoint string |

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

- **Transport:** UDS (real `AF_UNIX`, the default) or TCP (ZMQ, for a networked controller),
  chosen in the settings screen.
  UDS does **not** use JeroMQ's `ipc://` (which is TCP-emulated, not real `AF_UNIX`); instead the
  UDS path drops ZMTP entirely and uses a trivial length-prefixed framing over Java's built-in
  `AF_UNIX` sockets (JEP 380) — the link only needs conflated, fire-and-forget binary messages, so
  none of ZeroMQ's wire machinery is required. The Python controller (`pylib/`, imported as
  `ocl`) speaks both. See `pylib/README.md` for the UDS socket paths and the Flatpak-sandbox
  directory note.
- **Vision downsampling** maps the framebuffer onto the target resolution per-axis with no
  aspect-ratio correction; pick a camera width/height matching your window aspect to avoid
  a squashed image (the 256×144 default suits a typical 16:9 window).
