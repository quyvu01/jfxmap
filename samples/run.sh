#!/usr/bin/env bash
# Runs the two sample services on macOS and Linux: user-service (HTTP 8081, gRPC 9091), then order-service (HTTP 8082).
#
#   samples/run.sh           build, start both, show /orders, keep them running until Ctrl+C
#   samples/run.sh --check   build, start both, check /orders shows the users, stop both (exit code 1 on failure)
#
# Needs Java 21 (java on PATH, or JAVA_HOME) and curl.
set -euo pipefail

mode="run"
case "${1:-}" in
  "") ;;
  --check) mode="check" ;;
  -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (use --check or --help)" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
logs="$root/samples/build/logs"
java="java"
if [[ -n "${JAVA_HOME:-}" ]]; then java="$JAVA_HOME/bin/java"; fi
command -v "$java" >/dev/null || { echo "Java 21 is needed: put java on PATH or set JAVA_HOME." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is needed." >&2; exit 1; }

echo "Building the services..."
(cd "$root" && ./gradlew -q :samples:user-service:bootJar :samples:order-service:bootJar)

jar_of() {
  local jar
  jar="$(ls "$root/samples/$1/build/libs/"*.jar | grep -v -- '-plain\.jar$' | head -n 1)"
  [[ -n "$jar" ]] || { echo "No jar was built for $1." >&2; exit 1; }
  echo "$jar"
}

pids=()
stop() {
  for pid in "${pids[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  for pid in "${pids[@]:-}"; do
    [[ -n "$pid" ]] && wait "$pid" 2>/dev/null || true
  done
}
trap stop EXIT
trap 'exit 130' INT TERM

start() {
  local name="$1"
  mkdir -p "$logs"
  "$java" -jar "$(jar_of "$name")" >"$logs/$name.log" 2>&1 &
  pids+=("$!")
  echo "Started $name (pid $!), log: $logs/$name.log"
}

wait_for() {
  local name="$1" url="$2" pid="$3"
  for _ in $(seq 1 120); do
    # Up once it answers with its seeded data: the seeding runs just after the web server starts
    if [[ "$(curl -fs "$url" 2>/dev/null || true)" == "[{"* ]]; then echo "$name is up: $url"; return 0; fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$name stopped while starting; the end of its log:" >&2
      tail -n 40 "$logs/$name.log" >&2
      return 1
    fi
    sleep 1
  done
  echo "$name did not answer $url in 120 seconds; the end of its log:" >&2
  tail -n 40 "$logs/$name.log" >&2
  return 1
}

start user-service
wait_for user-service http://localhost:8081/users "${pids[0]}"
start order-service
wait_for order-service http://localhost:8082/orders "${pids[1]}"

orders="$(curl -fs http://localhost:8082/orders)"
echo
echo "GET http://localhost:8082/orders"
echo "$orders"
echo

if [[ "$mode" == "check" ]]; then
  for expected in '"userName":"Alice Nguyen"' '"userCountry":"France"' '"userAddressCount":2'; do
    if [[ "$orders" != *"$expected"* ]]; then
      echo "Check failed: /orders does not contain $expected" >&2
      exit 1
    fi
  done
  echo "Check passed: order-service filled the orders' user fields from user-service over gRPC."
  exit 0
fi

echo "Both services are running. Try:"
echo "  curl http://localhost:8082/orders/2"
echo "  curl http://localhost:8081/users"
echo "Press Ctrl+C to stop them."
wait
