# Implementation plan

## Selected stack

| Area | Technology | Responsibility |
| --- | --- | --- |
| Desktop application | Kotlin + Compose Multiplatform Desktop | Import, editor controls, state, job lifecycle, preview UI, export screen. |
| Native engine | C++20 | Stable, performance-sensitive video and AI pipeline. |
| Media | FFmpeg | Decode, timestamps, audio handling, final MP4 encoding. |
| Vision | OpenCV | Frame representation, morphology, alpha/outline compositing. |
| Inference | ONNX Runtime C++ | Run an on-device human segmentation model. |
| Build | Gradle (Kotlin) + CMake (C++) | Reproducible application and engine builds. |

The Kotlin app and C++ engine remain separate processes. Each render is described by a versioned JSON job file. The C++ engine reads input media paths, writes previews/exports, and emits newline-delimited JSON status events. This avoids JNI and avoids copying video frames between runtimes.

## Phase 0 — asset review and engine contract

1. Inspect the supplied videos for dimensions, FPS, duration, audio, and the expected visual result.
2. Confirm `background.MP4` / `final.MP4` are input and expected-output examples.
3. Define outline thickness behavior. Recommended default: 2 cm converted at a user-selectable 96 DPI (about 76 px), with a direct pixel control for video work.
4. Record acceptance examples: easy foreground, moving person, hair/transparent edges, subject partially leaving frame, and no-person input.
5. Specify the JSON job schema, progress/error events, exit codes, temporary-file policy, and engine version compatibility.

## Phase 1 — proof of concept pipeline

1. Decode frames while preserving timestamps and audio.
2. Run human instance segmentation and choose the dominant subject consistently.
3. Refine masks with temporal smoothing and edge feathering.
4. Expand the alpha mask, subtract the original mask, and fill the resulting ring white.
5. Composite over the background and encode MP4 with synchronized audio.

## Phase 2 — Kotlin desktop application

1. Scaffold a Kotlin/Compose desktop app with import, preview, timeline, and export screens.
2. Add controls for subject selection, outline width/color, feathering, placement, scale, and background fit.
3. Add correction keyframes for difficult frames.
4. Provide export progress, cancellation, and actionable errors.

The first preview will use engine-generated still/proxy frames for robust seek-and-inspect behavior. Smooth playback is a follow-up integration once we select and validate a desktop media-player component.

### Current implementation slice

The Kotlin application is scaffolded with the two required video inputs. Each has a native file chooser; drag-and-drop onto the application window assigns files in order (Reference, then Background). File extension validation happens before an input is accepted. The render button stays disabled until both fields are present, then marks the hand-off point for the future C++ engine.

The current integration milestone replaces that placeholder with a versioned JSON job and a native-engine invocation. The initial C++ executable validates input paths and emits structured progress events; it does **not** render a video yet. FFmpeg probing, segmentation, outline compositing, and MP4 export are the next engine milestones.

## Phase 3 — quality and release

1. Test codecs, portrait/landscape clips, rotation metadata, VFR footage, resolution changes, and audio edge cases.
2. Add fixture tests for mask stability, outline generation, and compositing.
3. Package the app and document hardware, model downloads, supported formats, and privacy.

## Proposed architecture

```
Kotlin + Compose Desktop UI
       |
JSON job file + JSON progress events
       |
C++ render engine
       |
FFmpeg decoder → ONNX Runtime segmentation → OpenCV mask refinement / outline / compositor → FFmpeg encoder
```

## MVP acceptance checklist

- Import a reference video and background video.
- Automatically identify one person and preview a transparent cutout.
- Render a configurable white outline.
- Position and scale the cutout over the background.
- Export a synchronized MP4 with progress.
- Give clear choices/errors for no-person and multi-person clips.
