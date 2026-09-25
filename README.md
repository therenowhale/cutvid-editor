# Jamal Video Compositor

Web application for isolating the main person in a reference video, adding a white outline, and compositing it over a background video.

## Current milestone

The local web UI provides two multi-video inputs:

- Reference video — person to cut out.
- Background video — destination footage.

Files are paired in selection order. Select the same number on each side for
one-to-one pairing, or select one file on either side to reuse it for all files
on the other side. A submission may create up to five outputs; all five begin
rendering in parallel. The page shows upload progress, render stage, elapsed
time, estimated remaining time, and a separate progress bar for every output.

`Render video` creates a local render job and invokes the C++ engine. The engine isolates the dominant person, applies a configurable outline, composites it over the background, loops a shorter background when needed, and preserves the reference video's audio.

## Run the web app

Build the native render engine first, then start the local web entry point:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
./gradlew :web-app:run
```

Open http://127.0.0.1:8787. Videos are uploaded only to this machine. Completed exports are saved to `~/.jamal/exports` (and offered as browser downloads); temporary uploads remain local in `~/.jamal`.

## Docker deployment

The production image builds the web app and native renderer together, includes
the ONNX-simplified MODNet model with its fixed `1×3×1024×576` input, and writes
all user uploads, render history, sessions, and exports to a persistent Docker
volume. The image verifies the model checksum during its build.

1. Copy `.env.example` to `.env` and set `GOOGLE_OAUTH_CLIENT_ID`,
   `GOOGLE_OAUTH_CLIENT_SECRET`, and `JAMAL_PUBLIC_URL`.
2. In Google Cloud Console, add
   `https://your-domain.example/auth/google/callback` as an authorised redirect
   URI. It must exactly match `JAMAL_PUBLIC_URL` plus that path.
3. Put the app behind HTTPS (for example Caddy or Nginx) and proxy requests to
   `http://127.0.0.1:8787`.
4. Start it:

```sh
docker compose up -d --build
docker compose logs -f jamal
```

The named `jamal-data` volume is intentionally retained by `docker compose
down`; it contains users' render history and exported videos. To expose the
container directly for a test server, browse to `http://SERVER_IP:8787` and set
`JAMAL_PUBLIC_URL` to that exact public address. For Google sign-in on a real
server, use HTTPS and the matching HTTPS URL.

Uploads require a Google sign-in. Each render is permanently associated with
the signed-in Google account, so its render history and downloads are shown
only to that account, including after restarting the app. Google access tokens
are used only during sign-in and are never stored.

If Google OAuth credentials are not configured, the upload page offers a local
browser-session mode instead, so the app remains usable without `.env` setup.
That mode keeps render history only in that browser session; configure Google
OAuth when users need account-based history across devices.

Create a Google OAuth **Web application** client, add
`http://127.0.0.1:8787/auth/google/callback` as an authorised redirect URI,
then put its credentials in the project-root `.env` file:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=your-google-client-secret
```

The repository includes `.env.example`, while the real `.env` is ignored by
Git. Restart the application after changing its configuration. Shell environment
variables and JVM system properties override values loaded from `.env`.

### Admin activity dashboard

Open [http://127.0.0.1:8787/admin](http://127.0.0.1:8787/admin). The dashboard
is unlocked and shows every persisted render job, its Google-account owner,
state, progress, and status message. It refreshes every two seconds.
It also provides a download of the current structured log.

Admin tokens may contain Unicode characters. The dashboard encodes the token
as UTF-8/Base64 before placing it in the authenticated request header; the
token itself is never persisted or logged by the server.

Activity is stored as newline-delimited JSON under `~/.jamal/logs`, rotated
daily and retained for 30 days. Logs include uploads, render pipeline changes,
render-engine output, progress, failures with stack traces, downloads,
admin authentication failures, and relevant HTTP requests. Tokens and raw MAC
addresses are never logged; sensitive detail keys are automatically redacted.

To use a different export folder, set `jamal.exports.dir` when starting the app:

```sh
./gradlew -Djamal.exports.dir="/path/to/exports" :web-app:run
```

For a direct end-to-end rendering verification, use:

```sh
./tests/render-smoke.sh
```
