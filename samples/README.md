# jfxmap samples

Two Spring Boot 4.1 services that use what jfxmap ports from FxMap's phase 2: the JPA data provider, the gRPC
transport and the Spring Boot starter.

| Service | Owns | Ports | What jfxmap does there |
|---|---|---|---|
| `user-service` | users, countries, addresses (JPA, H2) | HTTP 8081, gRPC 9091 | `UserConfig` exposes `User` under the string key `UserOf`; the starter answers it from JPA and serves it on gRPC |
| `order-service` | orders (JPA, H2), which hold only a `userId` | HTTP 8082 | `OrderResponseProfile` fills each order's user name, email, country, upper-case name and address count from `UserOf`, over gRPC; it compiles with the `JfxMapExpressions` javac plugin of jfxmap-validation, so an invalid expression in the profile fails the build |

`UserOf` is a string key, so a .NET FxMap service that declares `UseDistributedKey("UserOf")` can take either side.

[`dotnet-java-nats/`](dotnet-java-nats/README.md) does that: a .NET service on FxMap and EF Core and a Java service on
jfxmap and JPA, each on PostgreSQL with its migrations and seed data, mapping each other's data over NATS.

## Run

One script builds both services, starts user-service, waits for it, starts order-service, and prints `/orders`. Both
keep running until Ctrl+C; their logs are in `samples/build/logs/`. It needs Java 21 (on PATH or in `JAVA_HOME`).

| OS | Command |
|---|---|
| macOS, Linux | `samples/run.sh` |
| Windows (cmd or double-click) | `samples\run.cmd` |
| Windows (PowerShell) | `samples\run.ps1` |

Add `--check` (`-Check` on Windows) to only check that `/orders` shows the users' fields, then stop both; CI runs that
on all three systems.

Then:

```sh
curl localhost:8082/orders      # every order with its user's fields, in one gRPC call
curl localhost:8082/orders/2
curl localhost:8081/users
```

Or start each one yourself: `./gradlew :samples:user-service:bootRun`, then `./gradlew :samples:order-service:bootRun`.

## Test

`integration-test` starts both services in one JVM, on free ports, each with its own database, and checks what
`/orders` shows:

```sh
./gradlew :samples:integration-test:test
```

Each service also has its own test against a stand-in for the other.
