#!/usr/bin/env bash
set -euo pipefail

./gradlew compileJava --no-daemon
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
paper_api=$(find "$cache/io.papermc.paper/paper-api/26.3.build.249-stable" -name '*.jar' -print -quit)
adventure=$(find "$cache/net.kyori" -name '*.jar' -print | paste -sd: -)
bungee=$(find "$cache/net.md-5/bungeecord-chat" -name '*.jar' -print -quit)
annotations=$(find "$cache/org.jetbrains/annotations" -name '*.jar' -print -quit)
jspecify=$(find "$cache/org.jspecify/jspecify" -name '*.jar' -print -quit)
guava=$(find "$cache/com.google.guava/guava" -name '*.jar' -print -quit)
test -n "$paper_api" && test -n "$adventure" && test -n "$bungee" && test -n "$annotations" && test -n "$jspecify" && test -n "$guava"
mkdir -p run/port263-probe-classes run/plugins
javac -cp "$paper_api:$adventure:$bungee:$annotations:$jspecify:$guava" -d run/port263-probe-classes tools/ci/port-263-probe/Port263Probe.java
cp tools/ci/port-263-probe/plugin.yml run/port263-probe-classes/
jar cf run/plugins/CardboardPort263Probe.jar -C run/port263-probe-classes .
printf 'eula=true\n' > run/eula.txt
cat > run/server.properties <<'EOF'
online-mode=false
server-ip=127.0.0.1
level-name=port263-smoke
view-distance=3
simulation-distance=3
pause-when-empty-seconds=0
EOF

log="$PWD/port263-smoke.log"
pipe="$PWD/port263-smoke-input"
rm -f "$pipe"
mkfifo "$pipe"
./gradlew runServer --no-daemon < "$pipe" > "$log" 2>&1 &
server_pid=$!
exec 3>"$pipe"
cleanup() {
  if kill -0 "$server_pid" 2>/dev/null; then
    printf 'stop\n' >&3 || true
    sleep 3
    kill "$server_pid" 2>/dev/null || true
  fi
  exec 3>&- || true
  rm -f "$pipe"
}
trap cleanup EXIT

result=timeout
for _ in $(seq 1 240); do
  if grep -Eq 'PORT_263_PROBE_FAIL|Error occurred while (loading|enabling)|Mixin apply failed|\[[^]]+/ERROR\]|\[[^]]+/SEVERE\]' "$log"; then
    result=error
    break
  fi
  if grep -q 'Done (' "$log" && grep -q 'PORT_263_PROBE_OK' "$log"; then
    result=pass
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    result=exit
    break
  fi
  sleep 1
done
if [[ "$result" != pass ]]; then
  echo "::error::Minecraft 26.3 API smoke failed: $result"
  tail -n 200 "$log"
  exit 1
fi
printf 'stop\n' >&3
wait "$server_pid"
grep -q 'Goodbye!' "$log"
trap - EXIT
exec 3>&-
rm -f "$pipe"
