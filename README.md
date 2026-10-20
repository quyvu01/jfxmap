# jfxmap

FxMap for Java: a port of [FxMap](https://github.com/quyvu01/FxMap), the fluent distributed data-enrichment library.

Given a DTO with a foreign key (`userId`), jfxmap fills its related properties (`userName`, `userEmail`) from the
service that owns the entity, batching the ids of every object into one request per entity type.

> Status: early. Ported: the core, the expression language, the JPA data provider, the gRPC transport and a Spring
> Boot starter. Other transports (NATS, Kafka...) and data providers (MongoDB...) are not ported yet.

## Modules

| Module | Content |
|---|---|
| `jfxmap-core` | `ProfileOf` / `EntityConfigureOf` declarations, the `DistributedMapper` engine (id batching, dependency ordering, nested mapping, value write-back), the `RequestClient` and `QueryHandler` SPIs, and an in-memory handler that evaluates the full expression language (it depends on `jfxmap-expression`) |
| `jfxmap-jpa` | `JpaQueryHandler`, FxMap.EntityFrameworkCore's counterpart: loads the requested entities from a JPA persistence unit (Jakarta Persistence 3.2) and evaluates the expressions on them, following lazy associations |
| `jfxmap-grpc` | FxMap.Grpc's counterpart, on FxMap's own `fxMap.proto`: `FxMapGrpcService` answers for the owned keys, `GrpcRequestClient` finds the host that owns a key and calls it. Keys travel as FxMap 5.1.1 names them, so Java and .NET services call each other |
| `jfxmap-spring-boot-starter` | Auto-configuration for Spring Boot 4.1: profile and entity configuration beans, JPA handlers, the gRPC server and client from `jfxmap.*` properties |
| `jfxmap-expression` | The FxMap expression language (`Orders:where(Status = 'Done'):sum(Total)`): tokenizer, parser to a syntax tree, and an in-memory evaluator over records, JavaBeans, Maps and collections. See [expression.md](https://github.com/quyvu01/FxMap/blob/main/docs/expression.md) for the syntax |

## Usage

A distributed key, shared by both sides:

```java
public final class UserOf implements DistributedKey {}
```

The owner of the entity (provider side):

```java
public class UserConfig extends EntityConfigureOf<User> {
    @Override
    protected void configure() {
        id(User::getId);                          // required
        defaultProperty(User::getName);           // returned when a rule has no expression
        useDistributedKey(UserOf.class);          // or useDistributedKey("UserOf") without a shared type
        exposedName(User::getEmail, "UserEmail");
    }
}
```

The consumer (FxMap's `.Of(...).For(...)` is `.of(...).map(...)`, since `for` is a Java keyword):

```java
public class UserSummaryProfile extends ProfileOf<UserSummaryDto> {
    @Override
    protected void configure() {
        useDistributedKey(UserOf.class)
                .of(UserSummaryDto::getUserId)
                .map(UserSummaryDto::getUserName)                     // the default property
                .map(UserSummaryDto::getUserEmail, "UserEmail")       // an exposed name
                .map(UserSummaryDto::getCountryName, "Country.Name")  // a navigation
                .map(UserSummaryDto::getDisplayName, c -> c           // chosen per call
                        .when(ctx -> ctx.headers().containsKey("x-admin"))
                        .expression("UserEmail")
                        .otherwise("Name"));
    }
}
```

Build the mapper and map an object, a collection or a whole graph:

```java
DistributedMapper mapper = FxMap.builder()
        .addProfiles(new UserSummaryProfile())
        .requestClient(transportClient)   // a transport; LocalRequestClient answers in-process
        .throwIfException()
        .build();

mapper.map(dtos);                          // or mapAsync(dtos) for a CompletableFuture
```

Properties are named with method references (`Dto::getUserName`, `Dto::userName`), and written through a setter or
the field. Values travel as JSON, matched to Java property names without case, so a .NET owner can answer a Java
consumer.

## Spring Boot

Add `jfxmap-spring-boot-starter` with `jfxmap-jpa` and `jfxmap-grpc`, declare the profiles and entity configurations as
beans (`@Component`), and set the properties:

```properties
# The owner of an entity: answer its key from JPA, on gRPC
jfxmap.grpc.server.port=9091

# A consumer: where the owners of the keys it maps are
jfxmap.grpc.client.hosts=http://user-service:9091
jfxmap.throw-if-exception=true
```

Then inject `DistributedMapper`. [samples/](samples/README.md) has a user service and an order service built this way.

To call a .NET FxMap service, or be called by one, use a string key on both sides: `useDistributedKey("UserOf")` in
Java and `UseDistributedKey("UserOf")` in .NET.

## Build

Java 21 and the Gradle wrapper:

```sh
./gradlew build
```

## Expressions

```java
var evaluator = new ExpressionEvaluator();
Object total = evaluator.evaluate("Orders:where(Status = 'Done'):sum(Total)", customer);
Object page = evaluator.evaluate("Orders:orderBy(Total desc):limit(10).{Id, Total}", customer); // List<Map<String, Object>>
ExpressionNode tree = ExpressionParser.parse("{Id, (Nickname ?? Name) as DisplayName}");
```

Names written in PascalCase (`Total`) also find the Java property `total`. `Address.City` fails when `Address` is null;
`Address?.City` gives null.
