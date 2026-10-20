# Running the two services locally

How to run `catalog-service` (.NET) and `sales-service` (Java) on your machine without the run scripts: from a
terminal or from an IDE, one at a time, to debug them, read their SQL and try their Swagger and GraphQL endpoints. For
the one-command way, see [README.md](README.md#run).

## 1. What you need

| Tool | Version | Check |
|---|---|---|
| Docker with Compose (Docker Desktop on Windows and macOS, with Linux containers) | any recent | `docker compose version` |
| .NET SDK | 10 | `dotnet --version` |
| JDK | 21 | `java -version` |

Gradle is not needed: the repository's `gradlew` / `gradlew.bat` downloads it. The commands below run from the root of
the repository.

## 2. Start NATS and PostgreSQL

```sh
docker compose -f samples/dotnet-java-nats/docker-compose.yml up -d --wait
```

| Container | Port on your machine | What for |
|---|---|---|
| `nats` | 4222 (clients), 8222 (monitoring: http://localhost:8222) | the transport between the services |
| `postgres` | 5433 (user `postgres`, password `postgres`) | the databases `catalog` (.NET) and `sales` (Java) |

The databases start empty: each service creates its tables and seeds them when it starts. To start over with fresh
data, remove the containers with their volume and start them again:

```sh
docker compose -f samples/dotnet-java-nats/docker-compose.yml down -v
```

### With your own PostgreSQL and NATS

If PostgreSQL and NATS already run on your machine (in your own containers, for example), skip the step above and let
`local_run.sh` build and start both services on them:

```sh
samples/dotnet-java-nats/local_run.sh            # keeps running until Ctrl+C
samples/dotnet-java-nats/local_run.sh --check    # only checks both mappings, then stops
```

It prints the logs of both services in the terminal as they come, each line prefixed with `[catalog-service]` or
`[sales-service]`: one line per HTTP call (`HTTP GET /orders responded 200 in 35 ms`) and each SQL statement (see
[6. Read the SQL](#6-read-the-sql)). They are also kept in `samples/dotnet-java-nats/build/logs/`. `run.sh` does the
same.

Its defaults are the connection settings of FxMap's sample services. Change any of them with an environment variable,
e.g. `PG_PASSWORD=secret samples/dotnet-java-nats/local_run.sh`:

| Variable | Default | What |
|---|---|---|
| `PG_HOST`, `PG_PORT` | `localhost`, `5432` | the PostgreSQL server |
| `PG_USER`, `PG_PASSWORD` | `postgres`, `Abcd@2021` | its user |
| `CATALOG_DB` | `FxMapSampleCatalog` | the database of catalog-service; EF Core creates it when it is missing |
| `SALES_DB` | `postgres` | an existing database for sales-service |
| `SALES_SCHEMA` | `fxmap_sample_sales` | the schema of sales-service in `SALES_DB`; Flyway creates it when it is missing |
| `NATS_URL` | `nats://localhost:4222` | the NATS server |

sales-service uses a schema of an existing database because Flyway, unlike EF Core, does not create databases. Both
services seed their data only once, so the script can run again on the same databases. To start over, drop
`FxMapSampleCatalog` and the schema `fxmap_sample_sales`.

## 3. Start catalog-service (.NET)

```sh
dotnet run --project samples/dotnet-java-nats/catalog-service
```

Or open `samples/dotnet-java-nats/catalog-service/CatalogService.csproj` in Rider, Visual Studio or VS Code (C# Dev Kit)
and run or debug it. When it starts it applies the EF Core migrations (`Migrations/`, seeded from
`Data/CatalogSeed.cs`), then listens on http://localhost:5080.

The settings are in `appsettings.json`; override one with an environment variable (`__` for each level):

| Setting | Default | Environment variable |
|---|---|---|
| `Urls` | `http://localhost:5080` | `Urls` |
| `ConnectionStrings:Catalog` | `Host=localhost;Port=5433;Database=catalog;Username=postgres;Password=postgres` | `ConnectionStrings__Catalog` |
| `FxMap:Nats:Url` | `nats://localhost:4222` | `FxMap__Nats__Url` |

## 4. Start sales-service (Java)

```sh
./gradlew :samples:dotnet-java-nats:sales-service:bootRun        # gradlew.bat on Windows
```

Or open the root of the repository in IntelliJ IDEA (as a Gradle project) and run
`io.github.quyvu01.jfxmap.samples.sales.SalesServiceApplication`. When it starts, Flyway applies
`src/main/resources/db/migration` (`V1__create_tables.sql`, `V2__seed_data.sql`), then it listens on
http://localhost:8090.

The settings are in `src/main/resources/sales-service.properties`; override one with an argument
(`--server.port=8091`) or an environment variable:

| Setting | Default | Environment variable |
|---|---|---|
| `server.port` | `8090` | `SERVER_PORT` |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/sales` | `SPRING_DATASOURCE_URL` |
| `jfxmap.nats.url` | `nats://localhost:4222` | `JFXMAP_NATS_URL` |

The two services can start in any order: each one asks the other only when a request needs its data. While one of
them is down, the requests of the other that need its data fail once the NATS request times out; the ones that do not
(`/products`, `/customers`) keep working.

## 5. Try them

| | catalog-service (.NET) | sales-service (Java) |
|---|---|---|
| Swagger UI | http://localhost:5080/swagger | http://localhost:8090/swagger-ui.html |
| OpenAPI document | http://localhost:5080/swagger/v1/swagger.json | http://localhost:8090/v3/api-docs |
| GraphQL endpoint | http://localhost:5080/graphql (POST) | http://localhost:8090/graphql (POST) |
| GraphQL IDE | http://localhost:5080/graphql (Nitro, in a browser) | http://localhost:8090/graphiql |
| REST | `/products`, `/products/{id}`, `/reviews`, `/reviews/{id}` | `/orders`, `/orders/{id}`, `/customers` |

GraphQL queries to paste in the IDEs. Only the fields a query selects are asked from the other service, in one NATS
request per query:

```graphql
# catalog-service: the customer fields come from sales-service
{
  reviews { id productName rating customerName customerEmail customerCity customerOrderCount }
  review(id: 3) { comment customerName }
  products { id name price category }
}
```

```graphql
# sales-service: the product fields come from catalog-service
{
  orders { id customerName quantity productName unitPrice categoryName }
  order(id: 2) { productName unitPrice }
  customers { id name email city }
}
```

The same over HTTP:

```sh
curl -s localhost:8090/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ orders { id productName categoryName } }"}'
curl -s localhost:5080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ reviews { id customerName customerOrderCount } }"}'
```

`orders` in REST also has a `total` (unit price times quantity), computed once the order is mapped; GraphQL leaves it
out, since its product fields are resolved field by field.

## 6. Read the SQL

Both services print one line per HTTP call (`HTTP GET /orders responded 200 in 35 ms`; Serilog's request logging in
catalog-service, `RequestLoggingFilter` in sales-service) and each SQL statement to the console, with the values of
its parameters:

- **catalog-service** logs with Serilog to the console. `Microsoft.EntityFrameworkCore.Database.Command` at
  `Information` (in the `Serilog` section of `appsettings.json`) prints each command EF Core runs, and
  `EnableSensitiveDataLogging()` in `Program.cs` adds the parameter values. That includes the queries FxMap runs to
  answer `ProductOf` for sales-service. Set that level to `Warning` to hide them.

  ```
  [11:32:25 INF] Microsoft.EntityFrameworkCore.Database.Command
    Executed DbCommand (2ms) [Parameters=[], CommandType='Text', CommandTimeout='30']
  SELECT r."Id", r."ProductId", p."Name" AS "ProductName", ... FROM reviews AS r ...
  ```

- **sales-service** logs with Spring Boot's console logging. `logging.level.org.hibernate.SQL=DEBUG` prints each
  statement Hibernate runs (formatted by `spring.jpa.properties.hibernate.format_sql=true`), including the ones of
  jfxmap's JPA handler when it answers `CustomerOf`, and `logging.level.org.hibernate.orm.jdbc.bind=TRACE` prints the
  value of each parameter. Remove those lines from `sales-service.properties` to hide them.

  ```
  DEBUG ... [sales-service] [o-auto-1-exec-8] org.hibernate.SQL :
      select so1_0.id, c1_0.id, c1_0.name, ... from sales_order so1_0 join customer c1_0 ... where so1_0.id=?
  TRACE ... org.hibernate.orm.jdbc.bind : binding parameter (1:BIGINT) <- [3]
  ```

## 7. Change the data

- **catalog-service**: change the model or `CatalogSeed`, then add a migration in `catalog-service`:

  ```sh
  dotnet tool install -g dotnet-ef        # once
  cd samples/dotnet-java-nats/catalog-service
  dotnet ef migrations add <Name>
  ```

  The service applies it the next time it starts.
- **sales-service**: add a `V3__<what>.sql` next to the other migrations; Flyway applies it the next time the service
  starts. Do not edit `V1` or `V2` once they ran: Flyway checks them, and refuses to start when they changed. Run
  `docker compose ... down -v` to start over.

Keep the ids that point across services in step: the reviews of catalog-service use the customer ids of sales-service,
and its orders use the product ids of catalog-service.

## 8. Tests

```sh
dotnet test samples/dotnet-java-nats/catalog-service-tests          # .NET, against a stand-in sales-service
./gradlew :samples:dotnet-java-nats:sales-service:test              # Java, against a stand-in catalog-service
```

Both start their own PostgreSQL and NATS with Testcontainers, so Docker must run, but not the containers of step 2.
The test of both real services together is in [README.md](README.md#test).

## Troubleshooting

| What you see | Why, and what to do |
|---|---|
| `port is already allocated` from `docker compose` | Something else uses 4222, 8222 or 5433: stop it, or change the left side of `ports` in `docker-compose.yml` and the matching setting of both services |
| catalog-service fails with `Failed to connect to 127.0.0.1:5433` | PostgreSQL is not running: run step 2 |
| A service fails to start with a NATS connection error | NATS is not running: run step 2 |
| `/orders` or `/reviews` fails after a few seconds | The other service is not running, or is on another NATS |
| `Address already in use` on 5080 or 8090 | A service from an earlier run still runs (`run.sh` stops its own on Ctrl+C) |
| On its first start, catalog-service logs `[ERR] ... Failed executing DbCommand ... SELECT "MigrationId", "ProductVersion" FROM "__EFMigrationsHistory"` | Nothing is wrong: EF Core reads its history table before it creates it on an empty database, then applies the migrations |
| Flyway: `Validate failed: Migrations have failed validation` | A migration that already ran was edited: `docker compose ... down -v` and start again |
