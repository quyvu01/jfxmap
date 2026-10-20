#!/usr/bin/env bash
# Runs catalog-service (.NET, HTTP 5080) and sales-service (Java, HTTP 8090) against a PostgreSQL and a NATS that
# already run on your machine (no Docker started here). The defaults are those of FxMap's sample services:
# PostgreSQL on localhost:5432, user postgres, password Abcd@2021; NATS on nats://localhost:4222.
#
#   samples/dotnet-java-nats/local_run.sh           build, start both, keep them running until Ctrl+C
#   samples/dotnet-java-nats/local_run.sh --check   build, start both, check both mappings, stop both
#
# Change a default with an environment variable, e.g. PG_PASSWORD=secret samples/dotnet-java-nats/local_run.sh:
#   PG_HOST, PG_PORT, PG_USER, PG_PASSWORD   the PostgreSQL server
#   CATALOG_DB    the database of catalog-service, created by EF Core if missing (FxMapSampleCatalog)
#   SALES_DB      an existing database for sales-service (postgres)
#   SALES_SCHEMA  the schema of sales-service in SALES_DB, created by Flyway if missing (fxmap_sample_sales)
#   NATS_URL      the NATS server
#
# Needs the .NET 10 SDK, Java 21 (java on PATH, or JAVA_HOME) and curl.
set -euo pipefail

mode="run"
case "${1:-}" in
  "") ;;
  --check) mode="check" ;;
  -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (use --check or --help)" >&2; exit 2 ;;
esac

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-Abcd@2021}"
CATALOG_DB="${CATALOG_DB:-FxMapSampleCatalog}"
SALES_DB="${SALES_DB:-postgres}"
SALES_SCHEMA="${SALES_SCHEMA:-fxmap_sample_sales}"
NATS_URL="${NATS_URL:-nats://localhost:4222}"

sample="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$sample/../.." && pwd)"
build="$sample/build"
logs="$build/logs"
java="java"
if [[ -n "${JAVA_HOME:-}" ]]; then java="$JAVA_HOME/bin/java"; fi
command -v dotnet >/dev/null || { echo "The .NET 10 SDK is needed: https://dotnet.microsoft.com/download" >&2; exit 1; }
command -v "$java" >/dev/null || { echo "Java 21 is needed: put java on PATH or set JAVA_HOME." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is needed." >&2; exit 1; }

# Fails early, with a clear message, when PostgreSQL or NATS does not listen
reachable() {
  (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null
}
nats_hostport="${NATS_URL#nats://}"
reachable "$PG_HOST" "$PG_PORT" || { echo "No PostgreSQL on $PG_HOST:$PG_PORT: start it, or set PG_HOST / PG_PORT." >&2; exit 1; }
reachable "${nats_hostport%%:*}" "${nats_hostport##*:}" || { echo "No NATS on $NATS_URL: start it, or set NATS_URL." >&2; exit 1; }

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

echo "Building the services..."
(cd "$root" && ./gradlew -q :samples:dotnet-java-nats:sales-service:bootJar)
dotnet publish "$sample/catalog-service" -c Release -o "$build/catalog-service" --nologo -v quiet >/dev/null
jar="$(ls "$sample/sales-service/build/libs/"*.jar | grep -v -- '-plain\.jar$' | head -n 1)"
[[ -n "$jar" ]] || { echo "No jar was built for sales-service." >&2; exit 1; }

mkdir -p "$logs"
# Where the output of a service goes: its log file and, unless --check, the terminal too, each line prefixed with
# the service's name, so that the HTTP calls and the SQL of both services show as they happen
log_to() {
  : >"$logs/$1.log"
  if [[ "$mode" == "run" ]]; then
    # Line by line as they come (awk can wait for a whole block of input before it prints)
    tee -a "$logs/$1.log" | while IFS= read -r line; do printf '[%s] %s\n' "$1" "$line"; done
  else
    cat >>"$logs/$1.log"
  fi
}
# catalog-service reads appsettings.json from its working directory; the environment overrides its settings
(cd "$build/catalog-service" && \
  ConnectionStrings__Catalog="Host=$PG_HOST;Port=$PG_PORT;Database=$CATALOG_DB;Username=$PG_USER;Password=$PG_PASSWORD" \
  FxMap__Nats__Url="$NATS_URL" \
  exec dotnet CatalogService.dll) > >(log_to catalog-service) 2>&1 &
pids+=("$!")
echo "Started catalog-service (pid $!), log: $logs/catalog-service.log"
"$java" -jar "$jar" \
  "--spring.datasource.url=jdbc:postgresql://$PG_HOST:$PG_PORT/$SALES_DB?currentSchema=$SALES_SCHEMA" \
  "--spring.datasource.username=$PG_USER" \
  "--spring.datasource.password=$PG_PASSWORD" \
  "--spring.flyway.schemas=$SALES_SCHEMA" \
  "--spring.jpa.properties.hibernate.default_schema=$SALES_SCHEMA" \
  "--jfxmap.nats.url=$NATS_URL" > >(log_to sales-service) 2>&1 &
pids+=("$!")
echo "Started sales-service (pid $!), log: $logs/sales-service.log"

wait_for() {
  local name="$1" url="$2" pid="$3"
  for _ in $(seq 1 120); do
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

echo "Both services are running, on PostgreSQL $PG_HOST:$PG_PORT ($CATALOG_DB, $SALES_DB.$SALES_SCHEMA) and $NATS_URL."
echo "Swagger: http://localhost:5080/swagger and http://localhost:8090/swagger-ui.html"
echo "GraphQL: http://localhost:5080/graphql and http://localhost:8090/graphiql"
echo "Their logs (HTTP calls, SQL) show below, and are kept in $logs."
echo "Press Ctrl+C to stop them (your PostgreSQL and NATS keep running)."
wait
