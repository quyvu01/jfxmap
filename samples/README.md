# jfxmap samples

Two Spring Boot 4.1 services that use what jfxmap ports from FxMap's phase 2: the JPA data provider, the gRPC
transport and the Spring Boot starter.

| Service | Owns | Ports | What jfxmap does there |
|---|---|---|---|
| `user-service` | users, countries, addresses (JPA, H2) | HTTP 8081, gRPC 9091 | `UserConfig` exposes `User` under the string key `UserOf`; the starter answers it from JPA and serves it on gRPC |
| `order-service` | orders (JPA, H2), which hold only a `userId` | HTTP 8082 | `OrderResponseProfile` fills each order's user name, email, country, upper-case name and address count from `UserOf`, over gRPC |

`UserOf` is a string key, so a .NET FxMap service that declares `UseDistributedKey("UserOf")` can take either side.

## Run

```sh
./gradlew :samples:user-service:bootRun     # in one terminal
./gradlew :samples:order-service:bootRun    # in another
curl localhost:8082/orders                  # every order with its user's fields, in one gRPC call
curl localhost:8082/orders/2
curl localhost:8081/users
```

## Test

`integration-test` starts both services in one JVM, on free ports, each with its own database, and checks what
`/orders` shows:

```sh
./gradlew :samples:integration-test:test
```

Each service also has its own test against a stand-in for the other.
