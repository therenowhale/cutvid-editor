# Jamal C++ render engine

This executable is the native processing boundary. It receives one render job file and emits newline-delimited JSON events on standard output.

Current milestone: validates that the selected input paths exist. The next milestone adds FFmpeg probing and video decoding.

## Development build

With CMake installed:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
```

The Kotlin desktop app looks for `render-engine/build/jamal-render-engine` in development.
