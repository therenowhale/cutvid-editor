#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
source_engine="$project_root/render-engine/build/jamal-render-engine"
runtime_root="$project_root/render-engine/build/jamal-runtime"
runtime_engine="$runtime_root/jamal-render-engine"
runtime_libs="$runtime_root/lib"

[[ -x "$source_engine" ]] || { echo "Render engine is missing: $source_engine" >&2; exit 1; }

rm -rf "$runtime_root"
mkdir -p "$runtime_libs"
cp "$source_engine" "$runtime_engine"
chmod 755 "$runtime_engine"

queue=("$runtime_engine")

while ((${#queue[@]})); do
  binary="${queue[0]}"
  queue=("${queue[@]:1}")

  while IFS= read -r dependency; do
    case "$dependency" in
      /System/*|/usr/lib/*|@*) continue ;;
    esac

    [[ "$dependency" == *.dylib ]] || continue
    [[ -f "$dependency" ]] || { echo "Missing dynamic library: $dependency" >&2; exit 1; }

    library_name="$(basename "$dependency")"
    bundled_library="$runtime_libs/$library_name"
    if [[ ! -f "$bundled_library" ]]; then
      cp -L "$dependency" "$bundled_library"
      chmod 755 "$bundled_library"
      install_name_tool -id "@loader_path/$library_name" "$bundled_library"
      queue+=("$bundled_library")
    fi

    if [[ "$binary" == "$runtime_engine" ]]; then
      replacement="@executable_path/lib/$library_name"
    else
      replacement="@loader_path/$library_name"
    fi
    install_name_tool -change "$dependency" "$replacement" "$binary"
  done < <(otool -L "$binary" | tail -n +2 | sed -E 's/^[[:space:]]*([^[:space:]]+).*/\1/')
done

find "$runtime_root" -type f \( -name '*.dylib' -o -name 'jamal-render-engine' \) -exec codesign --force --sign - {} \;

rm -f "$project_root/render-engine/build/jamal-runtime.zip"
(cd "$runtime_root" && zip -qry ../jamal-runtime.zip jamal-render-engine lib)
