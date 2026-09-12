# Jamal Video Compositor

Desktop application for isolating the main person in a reference video, adding a white outline, and compositing it over a background video.

## Current milestone

The Kotlin/Compose desktop UI provides two video inputs:

- Reference video — person to cut out.
- Background video — destination footage.

Each input can be selected through the system file picker. Drag one or two supported video files onto the app window to fill Reference, then Background.

`Prepare render` creates a local render job and invokes the C++ engine. The engine isolates the dominant person, applies a configurable outline, composites it over the background, loops a shorter background when needed, and preserves the reference video's audio.

## Run

```sh
./gradlew :desktop-app:run
```

Use JDK 21 for reproducible builds. The engine needs FFmpeg, ONNX Runtime, OpenCV, and the local `models/u2net_human_seg.onnx` model. Configure and build it with CMake before the first run:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
ctest --test-dir render-engine/build --output-on-failure
```

For a direct end-to-end verification without CMake, use:

```sh
./tests/render-smoke.sh
```
