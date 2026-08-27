# Open Crafter Link for Minecraft

[![build](https://img.shields.io/github/actions/workflow/status/Kelvinlby/minecraft-link/build.yml?branch=main&style=for-the-badge&logo=github&label=Build)](https://github.com/Kelvinlby/minecraft-link/actions/workflows/build.yml)

Open Crafter Link connects a Minecraft client to an external controller. It lets a program observe
the player and the world, then control the player through the same movement, interaction, and
inventory actions available to a human.

This repository builds two client-side Fabric mods:

| Mod | File | Purpose |
|-----|------|---------|
| **Open Crafter Link** | `open-crafter-link-<version>.jar` | Live RGBD vision, player telemetry, remote control, and optional virtual cameras |
| **Open Crafter Dataset Recorder** | `open-crafter-recorder-<version>.jar` | Optional dataset capture from human play or recorded server packet sessions |

The recorder mod depends on Open Crafter Link. Install only the core JAR for ordinary controller
use, or install both JARs to create datasets.

## Features

### Open Crafter Link

- Streams player telemetry at Minecraft tick rate: rotation, selected hotbar slot, health, food,
  air, experience level, and the current inventory/container state.
- Streams a pre-HUD RGBD view of the world. RGB includes the rendered world and first-person hand;
  depth contains normalized eye-space distance.
- Accepts movement, sprinting, sneaking, jumping, absolute camera rotation, hotbar selection,
  attack, and use controls.
- Supports inventory operations such as pick, put, quick-move, swap, drop, distribute, and collect.
- Uses Unix domain sockets for a fast same-machine connection by default, with TCP available for
  controllers on another machine.
- Includes a Python library and command-line controller under [`pylib/`](pylib/README.md).
- Can expose RGB and depth as Linux virtual cameras for use in OBS, FFmpeg, browsers, and other
  camera applications.

The protocol definition and wire-format details are documented in
[`protocol/README.md`](protocol/README.md).

### Open Crafter Dataset Recorder

The optional recorder produces aligned observations and actions for training or analysis. A saved
session contains:

- `rgb.mp4` — RGB video encoded with a system-installed FFmpeg
- `depth.png.zip` — one 16-bit depth PNG per sample
- `actions.jsonl` — player actions, state, inventory, timestamps, and frame metadata
- `manifest.json` — session settings and final sample counts

It supports two recording modes:

- **Live recording:** record RGBD frames and actions while a person plays in single-player or
  multiplayer.
- **Packet replay:** turn a packet session recorded on a Paper/Folia server into a dataset inside a
  private, client-only replay world. Replay can run in real time or in eager mode as quickly as the
  renderer and encoder allow.

## Requirements and platform support

| Requirement | Support |
|-------------|---------|
| Minecraft | **1.21.11** |
| Mod loader | Fabric Loader **0.19.0 or newer** |
| Java | **21 or newer** |
| Operating systems | Linux, Windows, and macOS |
| Required mods | Fabric API and YACL 3 |
| Recommended mod | Mod Menu, for opening the settings screens |

The core link and dataset recording work on Linux, Windows, and macOS. A system FFmpeg installation
is needed to write `rgb.mp4`; depth and action data are still saved if FFmpeg is unavailable. GPU
encoding depends on the encoders exposed by the local FFmpeg build, and automatic mode can fall back
to CPU encoding.

Virtual cameras are **Linux-only** and require FFmpeg plus the `v4l2loopback` kernel module. Windows
and macOS can still use the RGBD network stream and dataset recorder normally.

Unix domain sockets are intended for a controller running on the same machine. Use TCP when the
controller is remote or when Unix sockets are unavailable in the local environment.

## Install and run

### 1. Install the mods

Install Minecraft 1.21.11 with Fabric Loader, then place these dependencies in the profile's
`mods/` directory:

- Fabric API
- YACL 3
- Mod Menu (recommended)

Add one or both Open Crafter JARs:

- For controller use: add `open-crafter-link-dev.jar`.
- For dataset recording: add both `open-crafter-link-dev.jar` and
  `open-crafter-recorder-dev.jar`.

Both Open Crafter JARs must have the same version.

### 2. Configure Open Crafter Link

Launch Minecraft and open **Mods → Open Crafter Link → Configure**.

The default transport is **UDS**, which is suitable when Minecraft and the controller run on the
same computer. Select **TCP** for a remote controller. The default TCP configuration listens for
telemetry and vision on `127.0.0.1` and connects to the controller at `127.0.0.1`.

Settings are stored in `config/open-crafter-link.json` and take effect when saved. Important options
include transport, controller address, input staleness, camera resolution, and virtual-camera
output.

### 3. Run the Python controller

Python 3.9 or newer is required. From this repository:

```bash
pip install ./pylib
```

Join a Minecraft world, then try:

```bash
ocl telemetry
ocl vision --frames 3 --dump-dir ocl-frames
ocl drive --forward --sprint --hold 2
ocl roundtrip --yaw 90 --pitch 0
```

The Python client uses UDS by default, matching the mod. Use `--transport tcp` when the mod is set
to TCP. See [`pylib/README.md`](pylib/README.md) for the library API, all CLI commands, endpoint
configuration, and Flatpak socket-directory setup.

## Record datasets

Open **Mods → Open Crafter Dataset Recorder → Configure**. Recorder settings are stored separately
in `config/open-crafter-recorder.json`.

### Record human play

1. Enable **Record dataset**.
2. Set the sample rate and video options if needed.
3. Join a single-player or multiplayer world and play normally.
4. Leave the world to finish the session. A toast reports save progress.

The completed dataset is written to:

```text
<game-directory>/open-crafter-link/recording/<timestamp>/
```

Recording uses the camera resolution configured in Open Crafter Link's **Sensors** settings.

### Create a dataset from packet recordings

Packet recordings come from the separate
[`Kelvinlby/recorder`](https://github.com/Kelvinlby/recorder) Paper/Folia server plugin. That plugin
records each player's client-to-server and server-to-client packets into a crash-tolerant `.mcrec`
session.

1. Install the server recorder plugin on a compatible Paper or Folia server.
2. Join the server and perform the gameplay you want to capture.
3. Disconnect so the plugin closes the session cleanly.
4. On the server, use `/recorder list` and `/recorder dump <recording> 20` to confirm that the
   session contains named packets with plausible timings.
5. Copy the complete session directory from `plugins/recorder/recordings/` into:

   ```text
   <game-directory>/open-crafter-link/replay/
   ```

   Keep `session.json` and every `.mcrec` segment together in that directory.
6. In the client recorder settings, enable **Record dataset** and **Auto replay**. Optionally enable
   **Eager encoding** to process the session faster than real time.
7. Return to the title screen. The mod processes the oldest session first and continues until the
   replay inbox is empty.

The recorded server packet protocol must exactly match this Minecraft 1.21.11 client. The server
recorder may support additional Minecraft versions, but sessions from those versions cannot be
replayed by this build.

After a replay and its dataset save both succeed, the source session moves to `replay/done/`.
Interrupted, incompatible, or failed sessions remain in `replay/` for inspection and retry. Output
datasets are saved under `open-crafter-link/recording/`, just like live recordings.

## Linux virtual cameras

To publish both RGB and depth as cameras, install FFmpeg and `v4l2loopback`, then load two devices:

```bash
sudo modprobe v4l2loopback devices=2 exclusive_caps=1,1 card_label="Minecraft RGB","Minecraft Depth"
```

Enable **RGB virtual camera** and/or **Depth virtual camera** under Open Crafter Link's **Sensors**
settings. Each enabled feed needs its own loopback device. With a Flatpak Minecraft launcher, device
access may also be required; for Prism Launcher:

```bash
flatpak override --user --device=all org.prismlauncher.PrismLauncher
```

## Build from source

Building requires Git and a Java 21 JDK. A separate Gradle installation is not needed because the
repository includes the Gradle wrapper.

On Linux or macOS:

```bash
git clone https://github.com/Kelvinlby/minecraft-link.git
cd minecraft-link
./gradlew build
```

On Windows PowerShell or Command Prompt, run `gradlew.bat build` instead.

The distributable JARs are placed in `build/libs/`:

```text
build/libs/open-crafter-link-dev.jar
build/libs/open-crafter-recorder-dev.jar
```

Useful Gradle tasks:

| Command | Purpose |
|---------|---------|
| `./gradlew build` | Build and test both mods |
| `./gradlew buildCore` | Build only Open Crafter Link |
| `./gradlew buildRecorder` | Build the recorder and its required core inputs |
| `./gradlew runCore` | Launch a development client with only the core mod |
| `./gradlew run` | Launch a development client with both mods |

Both development launch tasks use the repository's `run/` game directory.

## License

Open Crafter Link is licensed under the [GNU General Public License v3.0](LICENSE).
