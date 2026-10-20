# .NET + Java over NATS

Two services in two languages that fill each other's data with FxMap over NATS:

| Service | Language | Owns | Database | HTTP |
|---|---|---|---|---|
| `catalog-service` | .NET 10, FxMap 5.1.1, EF Core | categories, products, reviews | PostgreSQL `catalog`, EF Core migrations | 5080 |
| `sales-service` | Java 21, jfxmap, Spring Boot 4.1, JPA | customers, orders | PostgreSQL `sales`, Flyway | 8090 |

- A review in `catalog-service` holds only a customer id. `ReviewResponseProfile` fills the customer's name, email,
  city and order count from `CustomerOf`, which `sales-service` answers from JPA (`CustomerConfig`).
- An order in `sales-service` holds only a product id. `OrderResponseProfile` fills the product's name, unit price
  and category from `ProductOf`, which `catalog-service` answers from EF Core (`ProductConfig`).

Both keys are string keys (`UseDistributedKey("ProductOf")` in C#, `useDistributedKey("CustomerOf")` in Java): that
is what lets a .NET and a Java service name the same key.

The data comes from the migrations: `catalog-service/Migrations` (seeded from `Data/CatalogSeed.cs` with `HasData`) and
`sales-service/src/main/resources/db/migration` (`V1__create_tables.sql`, `V2__seed_data.sql`). The reviews use the
customer ids that Flyway seeds, and the orders use the product ids that EF Core seeds.

## Run

Needs Docker (with Compose; Docker Desktop with Linux containers on Windows and macOS), the .NET 10 SDK and Java 21.
The script starts NATS and PostgreSQL with `docker-compose.yml`, builds and starts both services, and prints what each
one mapped from the other. Everything keeps running until Ctrl+C, then the containers are removed; the logs are in
`samples/dotnet-java-nats/build/logs/`.

| OS | Command |
|---|---|
| macOS, Linux | `samples/dotnet-java-nats/run.sh` |
| Windows (cmd or double-click) | `samples\dotnet-java-nats\run.cmd` |
| Windows (PowerShell) | `samples\dotnet-java-nats\run.ps1` |

Add `--check` (`-Check` on Windows) to only check both mappings and stop. It uses ports 4222 (NATS), 5433
(PostgreSQL), 5080 and 8090.

Then:

```sh
curl localhost:8090/orders        # Java: every order with its product from .NET, in one NATS request
curl localhost:8090/orders/2      # two USB-C docks: unitPrice 129.90, total 259.80
curl localhost:5080/reviews       # .NET: every review with its customer from Java, in one NATS request
curl localhost:5080/reviews/3
curl localhost:5080/products      # the catalog alone
curl localhost:8090/customers     # the customers alone
```

Both services also have Swagger and GraphQL, and print each SQL statement they run to the console:

| | catalog-service (.NET) | sales-service (Java) |
|---|---|---|
| Swagger UI | http://localhost:5080/swagger | http://localhost:8090/swagger-ui.html |
| GraphQL | http://localhost:5080/graphql (Hot Chocolate + FxMap.HotChocolate) | http://localhost:8090/graphql, IDE on /graphiql (Spring for GraphQL + jfxmap-graphql) |
| SQL in the console | Serilog, EF Core's `Database.Command` logs | `org.hibernate.SQL` and `org.hibernate.orm.jdbc.bind` |

To run each service yourself (from a terminal or an IDE), try the GraphQL queries, read the SQL or change the data,
see [LOCAL-DEVELOPMENT.md](LOCAL-DEVELOPMENT.md).

## Test

- `catalog-service-tests`: catalog-service on a real PostgreSQL and NATS (Testcontainers), against a .NET stand-in for
  sales-service: `dotnet test samples/dotnet-java-nats/catalog-service-tests`
- `sales-service`: the same for the Java side, against a Java stand-in for catalog-service:
  `./gradlew :samples:dotnet-java-nats:sales-service:test`
- `integration-test`: both real services, each on its own database of one PostgreSQL, over one NATS. It needs the
  published catalog-service:

  ```sh
  dotnet publish samples/dotnet-java-nats/catalog-service -c Release -o samples/dotnet-java-nats/build/catalog-service
  JFXMAP_SAMPLE_CATALOG="dotnet $PWD/samples/dotnet-java-nats/build/catalog-service/CatalogService.dll" \
    ./gradlew :samples:dotnet-java-nats:integration-test:test
  ```

CI runs all three, and `run.sh --check` and `run.ps1 -Check` on Linux (the GitHub runners of Windows and macOS have no
Linux containers).
