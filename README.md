# Jamal Video Compositor

Desktop application for isolating the main person in a reference video, adding a white outline, and compositing it over a background video.

## Current milestone

The Kotlin/Compose desktop UI provides two video inputs:

- Reference video — person to cut out.
- Background video — destination footage.

Each input can be selected through the system file picker. Drag one or two supported video files onto the app window to fill Reference, then Background.

`Prepare render` is intentionally a placeholder until the C++ render engine is added. The engine contract is documented in `.codex/IMPLEMENTATION_PLAN.md`.

## Run (after Gradle wrapper is added)

```sh
./gradlew :desktop-app:run
```

Use JDK 21. The installed JDK 26 is newer than the declared project toolchain; installing JDK 21 is required for a reproducible local build.
