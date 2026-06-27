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

Run the client with vision enabled (and, if you want, custom resolution/rate):

```
-Docl.vision=true
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

# 3D RGBD "box": front face = the in-game image, perpendicular axis = depth
$PY ocl_test_controller.py pointcloud                       # live, corner view
$PY ocl_test_controller.py pointcloud --orbit-speed 1       # slowly spin to show depth
$PY ocl_test_controller.py pointcloud --angle 35 --elevation 25 --depth-scale 1.5
$PY ocl_test_controller.py pointcloud --save scene.ply      # one frame -> .ply, no GUI
#   -> a rectangular box seen from a corner: the front face shows the image exactly as
#      in-game, and each pixel is pushed back along the depth axis by its depth value.
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
- **pointcloud** — the RGBD frame renders as an extruded image-plane box whose front face
  matches the in-game view and whose depth axis lifts each pixel by its depth. Confirms RGB
  and depth are spatially registered, and lets you read the scene's depth structure at a glance.

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
`near`/`far` (blocks) travel in each frame's header, so the script reports both the
normalized value and the absolute distance in blocks (`norm * far`).
