# Open Crafter Link — test tools

`ocl_test_controller.py` stands in for the external controller and exercises every
link functionality against a running Minecraft client with the mod loaded.

## Setup

Per the project convention, use a `uv` environment:

```bash
cd tools
uv venv .venv
uv pip install --python .venv pyzmq         # required
uv pip install --python .venv pillow        # only for `vision --dump-dir` PNG dumps
uv pip install --python .venv numpy open3d  # only for the `pointcloud` 3D viewer
```

Vision is always on. If you want, set a custom resolution/rate at launch:

```
# optional: -Docl.visionWidth=128 -Docl.visionHeight=128 -Docl.visionMaxHz=40
```

Join a world (telemetry and vision only flow while a world is rendering).

## Wire roles

| Stream | Magic | Mod socket            | Controller socket      |
|--------|-------|-----------------------|------------------------|
| Telemetry | `OCLO` | PUB bind `*:5557` | SUB connect `localhost:5557` |
| Vision    | `OCLV` | PUB bind `*:5559` | SUB connect `localhost:5559` |
| Instructions | `OCLI` | SUB connect `localhost:5558` | PUB bind `*:5558` |

All sockets use `ZMQ_CONFLATE` (queue depth 1, newest wins).

## Usage

```bash
PY=.venv/bin/python

# Player-state telemetry: yaw/pitch/slot + live rate (~20 Hz)
$PY ocl_test_controller.py telemetry

# RGBD vision: rate, center-pixel depth, and dump frames to PNG (proves NO HUD)
$PY ocl_test_controller.py vision --dump-dir /tmp/ocl_frames --frames 3
#   -> inspect rgb_000.png (world + first-person hand, no hotbar/crosshair)
#      and depth_000.png (grayscale; nearer = darker)

# Drive the player. Movements must be re-sent each tick to "hold", so --hold N
# republishes for N seconds. Look is absolute degrees.
$PY ocl_test_controller.py drive --look 90 0          # face yaw 90
$PY ocl_test_controller.py drive --forward --sprint --hold 2
$PY ocl_test_controller.py drive --slot 4 --hold 0    # single shot
$PY ocl_test_controller.py drive --demo               # walk through every control

# Closed loop: send a rotation, read telemetry back, assert it actually changed
$PY ocl_test_controller.py roundtrip --yaw 123 --pitch -20

# Dashboard: telemetry + vision rates side by side for 10s
$PY ocl_test_controller.py all --seconds 10

# 3D point cloud: back-project the RGBD stream into a live Open3D window
$PY ocl_test_controller.py pointcloud                       # live, updates per frame
$PY ocl_test_controller.py pointcloud --fov 90 --max-blocks 96
$PY ocl_test_controller.py pointcloud --save scene.ply      # one frame -> .ply, no GUI
#   -> drag to orbit; red/green/blue axes = +X(right)/+Y(up)/+Z(toward viewer).
#      The scene in front of the player sits at negative Z.
```

## What each command proves

- **telemetry** — the `OCLO` stream decodes and runs at ~20 Hz (tick rate).
- **vision** — the `OCLV` stream decodes; rate clears the >20 Hz target; PNG dumps
  confirm the capture is the 3D world + hand with **no HUD** (the `END_MAIN` seam);
  center depth tracks the real distance to whatever you're aiming at (sky ≈ far).
- **drive / demo** — inbound `OCLI` instructions move/look/select/click the player.
- **roundtrip** — control + telemetry close the loop: a commanded yaw/pitch shows up
  in the telemetry within tolerance.
- **all** — telemetry and vision flow concurrently at independent cadences.
- **pointcloud** — the RGBD frame back-projects into a geometrically sensible 3D scene:
  each pixel's depth becomes a real distance (in blocks) and the colored points line up
  with the world you're looking at. Confirms RGB and depth are spatially registered.

## Point-cloud geometry

`pointcloud` reconstructs camera-space XYZ with a pinhole model: `Z = depth_norm · far`
(the wire depth is already linear eye-space distance ÷ far), then
`X = (u − cx)·Z/f` and `Y = (v − cy)·Z/f`, with focal length `f` derived from `--fov`
(vertical, default 70° — Minecraft's default). Sky / far-plane pixels (`depth ≈ 1`) and
anything past `--max-blocks` are dropped so the back wall doesn't smear.

> The focal length is taken from the **vertical** FOV and the frame's own height. If the
> captured frame's aspect ratio doesn't match the game window (see the vision aspect-ratio
> note in the main README), horizontal scale will be slightly off — set the camera
> resolution to your window's aspect for a correctly-proportioned cloud.

## Depth units

Depth on the wire is normalized `0..1` (= linear eye-space distance ÷ far plane).
`near`/`far` (blocks) travel in each frame's header, so the script reports both the
normalized value and the absolute distance in blocks (`norm * far`).
