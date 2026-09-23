#!/usr/bin/env bash
# Run a disposable Fabric 26.2 server with ordinary Bukkit/Paper plugins.
set -euo pipefail

log="${GITHUB_WORKSPACE:-$PWD}/common-plugins-smoke.log"
pipe="${GITHUB_WORKSPACE:-$PWD}/common-plugins-smoke-input"
mkdir -p run/plugins
printf 'eula=true\n' > run/eula.txt
cat > run/server.properties <<'EOF'
online-mode=false
server-ip=127.0.0.1
level-name=common-plugins-smoke
spawn-protection=0
view-distance=3
simulation-distance=3
pause-when-empty-seconds=0
EOF

download_plugin() {
  local name="$1" url="$2" hash="$3"
  local file="run/plugins/$name.jar"
  curl --fail --location --retry 3 --silent --show-error "$url" --output "$file"
  if [[ -n "$hash" ]]; then
    printf '%s  %s\n' "$hash" "$file" | sha256sum --check
  else
    # Print hashes for the initial CI run; pin these after confirming the upstream bytes.
    sha256sum "$file"
  fi
  if ! jar tf "$file" | grep -Eq '^(paper-plugin|plugin)\.yml$'; then
    echo "::error::$name download is not a Bukkit/Paper plugin JAR"
    exit 1
  fi
}

# Versions and immutable release assets are deliberately fixed so the smoke
# only changes when the plugin matrix is explicitly updated.
download_plugin LuckPerms \
  'https://cdn.modrinth.com/data/Vebnzrzj/versions/MBSY8toc/LuckPerms-Bukkit-5.5.53.jar' ''
download_plugin PlaceholderAPI \
  'https://cdn.modrinth.com/data/lKEzGugV/versions/pIvQcXW8/PlaceholderAPI-2.12.3.jar' ''
download_plugin EssentialsX \
  'https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsX-2.22.0.jar' \
  'bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6'
download_plugin EssentialsXChat \
  'https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsXChat-2.22.0.jar' \
  'e5b0211f98af1eaba712d9294997639a39209db1fc842394a0923820073ec65a'
download_plugin EssentialsXSpawn \
  'https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsXSpawn-2.22.0.jar' \
  'dd5377c4c921b9b67814209f4f6646ffbb959729003e721ec5e63c47c7c010b8'

rm -f "$pipe"
mkfifo "$pipe"
./gradlew runServer --no-daemon < "$pipe" > "$log" 2>&1 &
server_pid=$!
exec 3>"$pipe"

cleanup() {
  if kill -0 "$server_pid" 2>/dev/null; then
    printf 'stop\n' >&3 || true
    sleep 2
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" || true
  fi
  exec 3>&- || true
  rm -f "$pipe"
}
trap cleanup EXIT

result=timeout
for _ in $(seq 1 360); do
  if grep -Eq 'Failed to run bootstrapper|Could not load .+ in folder|Error occurred while (loading|enabling)|Mixin apply failed|\[/ERROR\]|\[/SEVERE\]' "$log"; then
    result=error
    break
  fi
  if grep -q 'Done (' "$log"; then
    result=ready
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    result=exit
    break
  fi
  sleep 1
done

if [[ "$result" == ready ]]; then
  # Catch errors emitted by plugins immediately after the server reports ready.
  sleep 5
  for plugin in LuckPerms PlaceholderAPI Essentials EssentialsChat EssentialsSpawn; do
    if ! grep -Eq "Enabling ${plugin} v[^[:space:]]+" "$log"; then
      result="missing-$plugin"
      break
    fi
  done
  if grep -Eq 'Failed to run bootstrapper|Could not load .+ in folder|Error occurred while (loading|enabling)|Mixin apply failed|\[/ERROR\]|\[/SEVERE\]' "$log"; then
    result=error
  fi
fi

grep -Ei 'Initialized [0-9]+ plugins|Bukkit plugins|Paper plugins|Loading server plugin|Enabling|Done \(|/ERROR\]|/SEVERE\]' "$log" || true
if [[ "$result" != ready ]]; then
  echo "::error::Common plugins server smoke failed: $result"
  tail -n 300 "$log"
  exit 1
fi

printf 'stop\n' >&3
wait "$server_pid"
trap - EXIT
exec 3>&-
rm -f "$pipe"
