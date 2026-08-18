# Project context — reference-video character cutout compositor

## Goal

Build an application that accepts a reference video, isolates its primary human subject, adds a white outline, and composites the result onto an output video.

## Current workspace assets

| File | Intended role | Status |
| --- | --- | --- |
| `reference.MOV` | Source video containing the person to isolate | Provided |
| `background.MP4` | Likely destination/background footage | Provided; needs confirmation |
| `final.MP4` | Existing output/reference example | Provided; needs inspection |

## Working assumptions to confirm

1. “2sm” means a **2 cm** white outline around the extracted human.
2. `background.MP4` is the video onto which the outlined cutout should be placed.
3. The intended output retains the reference video's timing and frame rate unless the UI specifies otherwise.
4. The application will initially support one dominant human subject per input clip.

## Key technical decisions still open

- Product surface: local desktop app, browser-based local app, or command-line first.
- Segmentation engine: local model versus a hosted inference API.
- Placement behavior: fixed position/scale versus keyframe controls and tracking.
- “2 cm” must be translated to pixels. A video file has no physical size without a target display/print DPI; the app should expose a pixel width and optionally convert from cm using an explicit DPI setting.

## Success criteria

- Subject matte remains stable across frames, including edges and motion.
- The white outline is clean, continuous, and has configurable thickness.
- Audio and output video timing remain synchronized.
- Users can preview and export a standard MP4 without command-line work.
