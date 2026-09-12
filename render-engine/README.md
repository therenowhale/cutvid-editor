# Jamal C++ render engine

This executable is the native processing boundary. It receives one render job file and emits newline-delimited JSON events on standard output.

The engine validates sources, probes media with FFmpeg, segments the dominant person with local U²-Net inference, applies a short temporal mask blend, composites an outline, and muxes the reference audio into the final MP4.

## Development build

With CMake installed:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
ctest --test-dir render-engine/build --output-on-failure
```

The Kotlin desktop app looks for `render-engine/build/jamal-render-engine` in development.

## Installable engine

```sh
cmake --install render-engine/build --prefix dist/render-engine
```

The installed binary still requires its documented runtime libraries (FFmpeg, OpenCV, ONNX Runtime) and the local segmentation model.
