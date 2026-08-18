# Implementation plan

## Phase 0 — product decisions and asset review

1. Inspect the supplied videos for dimensions, FPS, duration, audio, and the expected visual result.
2. Confirm target platform and whether `background.MP4` / `final.MP4` are input and expected-output examples.
3. Define outline thickness behavior. Recommended default: 2 cm converted at a user-selectable 96 DPI (about 76 px), with a direct pixel control for video work.
4. Record acceptance examples: easy foreground, moving person, hair/transparent edges, subject partially leaving frame, and no-person input.

**Deliverable:** concise product specification and selected technology stack.

## Phase 1 — proof of concept pipeline

1. Decode the reference video frame-by-frame while preserving timestamps and audio.
2. Run human instance segmentation and choose the dominant subject consistently.
3. Refine masks with temporal smoothing, edge feathering, and optional manual correction points.
4. Generate the outline by expanding the alpha mask, subtracting the original mask, and filling that ring white.
5. Composite the outlined subject over a chosen background, then encode MP4 with copied or mixed audio.
6. Compare the result against the reference and benchmark speed and visual artifacts.

**Deliverable:** repeatable local script with test clips and a sample output.

## Phase 2 — application experience

1. Create project/import flow for reference and background videos.
2. Add a preview player with timeline scrubbing.
3. Provide controls for subject selection, outline color and width, feathering, position, scale, and background fit.
4. Add a lightweight correction workflow for difficult frames (brush/erase mask edits or keyframe re-selection).
5. Show export settings, progress, cancellation, and clear failure messages.

**Deliverable:** usable MVP application that exports a reviewed MP4.

## Phase 3 — quality and reliability

1. Test supported codecs, portrait/landscape footage, variable frame rates, rotations, resolution changes, and missing audio.
2. Measure temporal-mask stability and compositing correctness with automated fixture tests.
3. Add resumable/cancellable jobs, disk-space checks, and safe cleanup of temporary frame data.
4. Package the app and document installation, model downloads, supported hardware, and privacy behavior.

**Deliverable:** release-ready build plus user and developer documentation.

## Proposed architecture (to validate in Phase 0)

```
UI / project controls
        |
job controller ── preview renderer
        |
video decoder → person segmentation → mask refinement → outline generator → compositor → encoder
                                      |                                      |
                                  edit/keyframe data                      MP4 output
```

## MVP acceptance checklist

- Import a reference video and a background video.
- Automatically identify one person and show the resulting transparent cutout.
- Render a configurable white outline; default follows the agreed 2 cm rule.
- Place and scale the cutout over the background in a preview.
- Export a synchronized MP4 with a visible progress indicator.
- Handle no-person and multi-person scenes with a clear user choice/error path.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Flickering masks between frames | Temporal consistency pass; allow correction keyframes. |
| Hair, hands, motion blur, or occlusion look poor | Matte refinement and manual correction for edge cases. |
| “2 cm” is ambiguous in pixels | Store physical unit plus explicit DPI, and expose pixels. |
| Slow processing on CPU-only hardware | Proxy preview, queued full-resolution export, hardware acceleration when present. |
| Proprietary/mobile codecs fail to decode | Validate inputs early and transcode internally where appropriate. |
