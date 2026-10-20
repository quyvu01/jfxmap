#!/usr/bin/env bash
# Runs the .NET + Java sample on macOS and Linux: NATS and PostgreSQL in Docker, then catalog-service (.NET, HTTP 5080)
# and sales-service (Java, HTTP 8090), which map each other's data over NATS.
#
#   samples/dotnet-java-nats/run.sh           start everything, show /orders and /reviews, keep running until Ctrl+C
#   samples/dotnet-java-nats/run.sh --check   start everything, check both mappings, stop everything (exit code 1 on failure)
#
# Needs Docker (with Compose), the .NET 10 SDK, Java 21 (java on PATH, or JAVA_HOME) and curl.
set -euo pipefail

mode="run"
case "${1:-}" in
  "") ;;
  --check) mode="check" ;;
  -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (use --check or --help)" >&2; exit 2 ;;
esac

sample="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$sample/../.." && pwd)"
build="$sample/build"
logs="$build/logs"
java="java"
if [[ -n "${JAVA_HOME:-}" ]]; then java="$JAVA_HOME/bin/java"; fi
command -v docker >/dev/null || { echo "Docker is needed (with Compose): https://docs.docker.com/get-docker/" >&2; exit 1; }
command -v dotnet >/dev/null || { echo "The .NET 10 SDK is needed: https://dotnet.microsoft.com/download" >&2; exit 1; }
command -v "$java" >/dev/null || { echo "Java 21 is needed: put java on PATH or set JAVA_HOME." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is needed." >&2; exit 1; }
compose=(docker compose -f "$sample/docker-compose.yml")

pids=()
stop() {
  for pid in "${pids[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  for pid in "${pids[@]:-}"; do
    [[ -n "$pid" ]] && wait "$pid" 2>/dev/null || true
  done
  echo "Stopping NATS and PostgreSQL..."
  "${compose[@]}" down -v >/dev/null 2>&1 || true
}
trap stop EXIT
trap 'exit 130' INT TERM

echo "Starting NATS and PostgreSQL..."
"${compose[@]}" up -d --wait

echo "Building the services..."
(cd "$root" && ./gradlew -q :samples:dotnet-java-nats:sales-service:bootJar)
dotnet publish "$sample/catalog-service" -c Release -o "$build/catalog-service" --nologo -v quiet >/dev/null
jar="$(ls "$sample/sales-service/build/libs/"*.jar | grep -v -- '-plain\.jar$' | head -n 1)"
[[ -n "$jar" ]] || { echo "No jar was built for sales-service." >&2; exit 1; }

mkdir -p "$logs"
# catalog-service reads appsettings.json from its working directory
(cd "$build/catalog-service" && exec dotnet CatalogService.dll) >"$logs/catalog-service.log" 2>&1 &
pids+=("$!")
echo "Started catalog-service (pid $!), log: $logs/catalog-service.log"
"$java" -jar "$jar" >"$logs/sales-service.log" 2>&1 &
pids+=("$!")
echo "Started sales-service (pid $!), log: $logs/sales-service.log"

wait_for() {
  local name="$1" url="$2" pid="$3"
  for _ in $(seq 1 120); do
    # Up once it answers with its seeded data: the migrations run before the web server starts
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

wait_for catalog-service http://localhost:5080/products "${pids[0]}"
wait_for sales-service http://localhost:8090/customers "${pids[1]}"

orders="$(curl -fs http://localhost:8090/orders)"
reviews="$(curl -fs http://localhost:5080/reviews)"
echo
echo "GET http://localhost:8090/orders   (Java, with the products of .NET)"
echo "$orders"
echo
echo "GET http://localhost:5080/reviews  (.NET, with the customers of Java)"
echo "$reviews"
echo

if [[ "$mode" == "check" ]]; then
  for expected in '"productName":"ThinkPad X1 Carbon"' '"categoryName":"Accessories"' '"total":259.8'; do
    [[ "$orders" == *"$expected"* ]] || { echo "Check failed: /orders does not contain $expected" >&2; exit 1; }
  done
  for expected in '"customerName":"Alice Nguyen"' '"customerCity":"Lyon"' '"customerOrderCount":3'; do
    [[ "$reviews" == *"$expected"* ]] || { echo "Check failed: /reviews does not contain $expected" >&2; exit 1; }
  done
  echo "Check passed: each service filled its data from the other over NATS."
  exit 0
fi

echo "Everything is running. Try:"
echo "  curl http://localhost:8090/orders/2"
echo "  curl http://localhost:5080/reviews/3"
echo "  curl http://localhost:5080/products"
echo "  curl http://localhost:8090/customers"
echo "Press Ctrl+C to stop."
wait
