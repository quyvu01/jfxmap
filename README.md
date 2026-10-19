# jfxmap

FxMap for Java: a port of [FxMap](https://github.com/quyvu01/FxMap), the fluent distributed data-enrichment library.

Given a DTO with a foreign key (`userId`), jfxmap fills its related properties (`userName`, `userEmail`) from the
service that owns the entity, batching the ids of every object into one request per entity type.

> Status: early. `jfxmap-core` holds the mapping model and engine. Transports (NATS, gRPC, Kafka...), data providers
> (JPA, MongoDB...) are not ported yet.

## Modules

| Module | Content |
|---|---|
| `jfxmap-core` | `ProfileOf` / `EntityConfigureOf` declarations, the `DistributedMapper` engine (id batching, dependency ordering, nested mapping, value write-back), the `RequestClient` and `QueryHandler` SPIs, and an in-memory handler that evaluates the full expression language (it depends on `jfxmap-expression`) |
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
