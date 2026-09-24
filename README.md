# Jamal Video Compositor

Local web application for isolating the main person in a reference video, adding a white outline, and compositing it over a background video. The existing desktop app remains available as an alternative.

## Current milestone

The local web UI provides two multi-video inputs:

- Reference video — person to cut out.
- Background video — destination footage.

Files are paired in selection order. Select the same number on each side for
one-to-one pairing, or select one file on either side to reuse it for all files
on the other side. Outputs are placed into a FIFO queue and rendered one at a
time. The page shows upload progress, queue position, render stage, elapsed
time, estimated remaining time, and a separate progress bar for every output.

`Render video` creates a local render job and invokes the C++ engine. The engine isolates the dominant person, applies a configurable outline, composites it over the background, loops a shorter background when needed, and preserves the reference video's audio.

## Run the web app

Build the native render engine first, then start the local web entry point:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
./gradlew :web-app:run
```

Open http://127.0.0.1:8787. Videos are uploaded only to this machine. Completed exports are saved to `OneDrive-Personal/Jamal Video Compositor/Exports` (and offered as browser downloads); temporary uploads remain local in `~/.jamal`.

Each machine gets three free renders. Local secrets are read automatically from
the project-root `.env` file:

```dotenv
JAMAL_ACCESS_TOKEN=replace-with-a-long-random-user-token
JAMAL_ADMIN_TOKEN=replace-with-a-different-long-random-admin-token
```

The repository includes `.env.example`, while the real `.env` is ignored by
Git. Restart the application after changing either token. Shell environment
variables and JVM system properties override values loaded from `.env`.

### Admin activity dashboard

Set a separate `JAMAL_ADMIN_TOKEN` in `.env` and open
[http://127.0.0.1:8787/admin](http://127.0.0.1:8787/admin).

If `JAMAL_ADMIN_TOKEN` is omitted, the render access token is also accepted by
the admin panel. The dashboard shows current job totals and states, per-job
progress, device/quota data, server uptime, and a searchable live event stream.
It also provides a download of the current structured log.

Admin tokens may contain Unicode characters. The dashboard encodes the token
as UTF-8/Base64 before placing it in the authenticated request header; the
token itself is never persisted or logged by the server.

Activity is stored as newline-delimited JSON under `~/.jamal/logs`, rotated
daily and retained for 30 days. Logs include uploads, quota decisions, queue
changes, render-engine output, progress, failures with stack traces, downloads,
admin authentication failures, and relevant HTTP requests. Tokens and raw MAC
addresses are never logged; sensitive detail keys are automatically redacted.

The allowance is stored in `~/.jamal/usage.properties` against a SHA-256 hash
derived from the local machine's network hardware identifiers. Raw MAC
addresses and entered access tokens are never written to disk or logs. Because
the web server is local-only (`127.0.0.1`), changing browser or refreshing the
page does not reset the machine allowance. If this app is later hosted on a
remote server, use account-based authentication instead: web browsers cannot
provide a remote server with the client's MAC address.

To use a different shared folder, such as Dropbox, set `jamal.exports.dir` when starting the app:

```sh
./gradlew -Djamal.exports.dir="$HOME/Dropbox/Jamal Video Compositor/Exports" :web-app:run
```

## Run the desktop app

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
