# Current status — 2026-08-22

## What is implemented

- Kotlin + Compose Desktop application with a fixed 940×760 window.
- Reference and background video inputs; each supports Finder file selection.
- Drag-and-drop accepts video files and assigns Reference first, then Background.
- `Prepare render` creates a JSON render job in `~/.jamal/jobs/` and starts the C++ engine.
- C++ engine validates source paths, probes streams with FFmpeg, loads the local U²-Net human-segmentation model, and writes structured JSON status events.
- The engine renders an MP4 to `~/.jamal/exports/`, looping a shorter background to the reference-video duration.
- A per-render log is written under `~/.jamal/exports/logs/`.
- Current composition places a cropped person at lower-left, with a white outline.

## Local dependencies installed on this Mac

- FFmpeg: `/opt/homebrew/opt/ffmpeg`
- ONNX Runtime: `/opt/homebrew/opt/onnxruntime`
- OpenCV 5: `/opt/homebrew/opt/opencv`
- Local model, excluded from Git: `models/u2net_human_seg.onnx`

## Important implementation paths

- Kotlin UI: `desktop-app/src/main/kotlin/com/jamal/app/Main.kt`
- C++ renderer: `render-engine/src/main.cpp`
- C++ build configuration: `render-engine/CMakeLists.txt`
- Native engine development binary: `render-engine/build/jamal-render-engine`
- Project plan: `.codex/IMPLEMENTATION_PLAN.md`

## Last observed output and issue

The previous generated export had a serious matte defect: a rectangular part of the original outdoor source background remained inside the supposed person cutout.

Cause identified:

1. U²-Net RGB channel normalization was missing before inference.
2. The compositor used a soft mask, allowing source-background pixels through.

Fix already made and compiled:

1. Normalize RGB input channels using U²-Net/ImageNet means and deviations.
2. Threshold the mask to a binary human alpha before cropping/compositing.
3. Use that binary alpha for the person layer and white outline.

## Required verification before claiming completion

1. Restart the app: `./gradlew :desktop-app:run`.
2. Select the reference and background videos again, then choose **Prepare render**.
3. Wait for `Export completed: …`.
4. Inspect the newest MP4 in `~/.jamal/exports/` at several timestamps.
5. Confirm that only the person remains from the source video—no rectangular source background, white holes, or flickering mask.
6. Compare it with `vids/final.MP4`; the desired visual is a compact lower-left person cutout with a thin clean white outline.

## Work still required

- Verify the latest mask fix visually; tune threshold/model preprocessing if artifacts remain.
- Add temporal smoothing to reduce frame-to-frame mask flicker.
- Add audio to the final MP4 (the current OpenCV VideoWriter output is video-only).
- Add automated renderer tests using short fixture clips and image-mask assertions.
- Add a proper CMake-based build/packaging path; CMake was not installed during development, so the local binary was compiled with `clang++` and Homebrew paths.
- Expose placement, scale, outline width/color, and export destination in the Kotlin UI.

## Current definition of done

The project is not finished until a manual test confirms a clean person-only cutout in the final MP4, with stable white outline and audio preserved or deliberately selectable.
