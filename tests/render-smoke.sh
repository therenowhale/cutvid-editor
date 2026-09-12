#!/bin/zsh
set -euo pipefail

root="${0:A:h:h}"
engine="${1:-$root/render-engine/build/jamal-render-engine}"
ffmpeg="/opt/homebrew/opt/ffmpeg/bin/ffmpeg"
ffprobe="/opt/homebrew/opt/ffmpeg/bin/ffprobe"
work="$(mktemp -d "${TMPDIR:-/tmp}/jamal-render-test.XXXXXX")"
duration="${JAMAL_TEST_DURATION:-1.2}"
trap 'rm -rf "$work"' EXIT

[[ -x "$engine" ]] || { print -u2 "Engine is not executable: $engine"; exit 1; }
[[ -x "$ffmpeg" && -x "$ffprobe" ]] || { print -u2 "Homebrew FFmpeg is required."; exit 1; }

"$ffmpeg" -y -v error -t "$duration" -i "$root/vids/reference.MOV" -c:v libx264 -c:a aac "$work/reference.mp4"
"$ffmpeg" -y -v error -t "$duration" -i "$root/vids/background.MP4" -c:v libx264 -c:a aac "$work/background.mp4"
cat > "$work/job.json" <<JSON
{"version":1,"referenceVideo":"$work/reference.mp4","backgroundVideo":"$work/background.mp4","outputVideo":"$work/export.mp4","outlinePixels":"12","scalePercent":"46","horizontalPercent":"2","bottomPercent":"3","outlineColor":"#FFFFFF"}
JSON

(cd "$root" && "$engine" "$work/job.json")
[[ -s "$work/export.mp4" ]] || { print -u2 "No export was produced."; exit 1; }
[[ "$("$ffprobe" -v error -select_streams v:0 -show_entries stream=codec_type -of csv=p=0 "$work/export.mp4")" == "video" ]] || exit 1
[[ "$("$ffprobe" -v error -select_streams a:0 -show_entries stream=codec_type -of csv=p=0 "$work/export.mp4")" == "audio" ]] || { print -u2 "Export has no audio stream."; exit 1; }
print "Render smoke test passed (video and reference audio present)."
if [[ "${JAMAL_KEEP_TEST_OUTPUT:-0}" == "1" ]]; then
    trap - EXIT
    print "Kept test export at: $work/export.mp4"
fi
