# jfxmap

FxMap for Java: a port of [FxMap](https://github.com/quyvu01/FxMap), the fluent distributed data-enrichment library.

Given a DTO with a foreign key (`userId`), jfxmap fills its related properties (`userName`, `userEmail`) from the
service that owns the entity, batching the ids of every object into one request per entity type.

> Status: early. Ported: the core, the expression language, the JPA and MongoDB data providers, the gRPC, NATS,
> RabbitMQ, Kafka, AWS SQS and Azure Service Bus transports, a GraphQL integration and a Spring Boot starter.

## Modules

| Module | Content |
|---|---|
| `jfxmap-core` | `ProfileOf` / `EntityConfigureOf` declarations, the `DistributedMapper` engine (id batching, dependency ordering, nested mapping, value write-back), the `RequestClient` and `QueryHandler` SPIs, and an in-memory handler that evaluates the full expression language (it depends on `jfxmap-expression`) |
| `jfxmap-jpa` | `JpaQueryHandler`, FxMap.EntityFrameworkCore's counterpart: translates the expressions into SQL (JPA Criteria, Jakarta Persistence 3.2), so that navigations, functions, conditions and the aggregations of collections run in the database, in one query per batch of ids (plus one per list of collection items). Only collections of whole entities, `:groupBy` and non-persistent properties are evaluated in memory on the loaded entities; `runsInDatabase(expression)` tells which |
| `jfxmap-grpc` | FxMap.Grpc's counterpart, on FxMap's own `fxMap.proto`: `FxMapGrpcService` answers for the owned keys, `GrpcRequestClient` finds the host that owns a key and calls it. Keys travel as FxMap 5.1.1 names them, so Java and .NET services call each other |
| `jfxmap-mongodb` | `MongoQueryHandler`, FxMap.MongoDb's counterpart: loads the requested documents from a `MongoCollection` (`$in` on the id, in batches of 1000) and evaluates the expressions on them |
| `jfxmap-nats` | FxMap.Nats's counterpart: `NatsServer` answers on `fxmap-{namespace}-{name}` subjects (with an optional prefix), `NatsRequestClient` sends requests on them |
| `jfxmap-rabbitmq` | FxMap.RabbitMq's counterpart: `RabbitMqServer` binds `fxmap-rpc-queue-{app}` to one direct exchange per key, `RabbitMqRequestClient` replies through its own `fxmap-reply-*` queue |
| `jfxmap-kafka` | FxMap.Kafka's counterpart: `KafkaServer` consumes `fxmap-request-topic-{key}` in group `fxmap-rpc-server-group`, `KafkaRequestClient` reads replies from its own `fxmap-response-topic-*` |
| `jfxmap-sqs` | FxMap.Aws.Sqs's counterpart: `SqsServer` long-polls `fxmap-{namespace}-{name}` queues (dots as dashes), `SqsRequestClient` reads replies from its own `fxmap-response-*` queue |
| `jfxmap-azure-servicebus` | FxMap.Azure.ServiceBus's counterpart: `ServiceBusServer` processes the sessions of `fxmap-request-{key}` (with an optional prefix), `ServiceBusRequestClient` reads its own session of `fxmap-reply-{key}` |
| `jfxmap-graphql` | FxMap.HotChocolate's counterpart, on graphql-java: `JfxMapGraphQl` resolves the mapped fields of a type through a DataLoader, so a query asks once per key for the fields it selects |
| `jfxmap-spring-boot-starter` | Auto-configuration for Spring Boot 4.1: profile and entity configuration beans, JPA and MongoDB handlers, the server and client of each transport from `jfxmap.*` properties |
| `jfxmap-expression` | The FxMap expression language (`Orders:where(Status = 'Done'):sum(Total)`): tokenizer, parser to a syntax tree, and an in-memory evaluator over records, JavaBeans, Maps and collections. See [expression.md](https://github.com/quyvu01/FxMap/blob/main/docs/expression.md) for the syntax |
| `jfxmap-validation` | FxMap.Analyzers' counterpart: the javac plugin `JfxMapExpressions` reports an invalid expression of a profile as a compile error (`OFX001`), and `JfxMapValidator` checks profiles and entity configurations at startup |

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

Other transports are turned on by their properties (the server answers the keys of the handlers, the client becomes
the `RequestClient`; set `jfxmap.<transport>.server-enabled=false` for a consumer only):

```properties
jfxmap.nats.url=nats://nats:4222
jfxmap.rabbitmq.host=rabbitmq           # also port, virtual-host, username, password, ssl
jfxmap.kafka.bootstrap-servers=kafka:9092
jfxmap.application-name=user-service    # names the RabbitMQ and Kafka queues; spring.application.name otherwise
jfxmap.sqs.region=us-east-1             # also endpoint, access-key-id, secret-access-key; or an SqsClient bean
jfxmap.servicebus.connection-string=Endpoint=sb://...   # also topic-prefix, create-queues, max-concurrent-sessions
```

Service Bus queues need sessions. The server creates the missing ones through the administration API
(`jfxmap.servicebus.create-queues=false` to skip it); with the emulator, which has no such API, declare them in its
config, and set `jfxmap.servicebus.try-timeout=3s` so new sessions are picked up quickly.

With Spring for GraphQL (`spring-boot-starter-graphql`) and `jfxmap-graphql`, the mapped properties of each profiled
type become fields resolved by jfxmap. Name the schema type of a class when it is not its simple name, and choose the
HTTP headers that reach the `MapContext`:

```properties
jfxmap.graphql.type-names[com.example.orders.OrderView]=Order
jfxmap.graphql.forwarded-headers=authorization
```

Without Spring, `JfxMapGraphQl.builder(mapper).build().configure(runtimeWiringBuilder)` does the same.

`MongoCollection` beans get a `MongoQueryHandler` each, for the entity configuration of their document class.

Then inject `DistributedMapper`. [samples/](samples/README.md) has a user service and an order service built this way.

To call a .NET FxMap service, or be called by one, use a string key on both sides: `useDistributedKey("UserOf")` in
Java and `UseDistributedKey("UserOf")` in .NET. The subjects, exchanges, queues, topics, headers and JSON match what
FxMap 5.1.1 sends; [interop/](interop/) checks it against a real .NET FxMap service on NATS, RabbitMQ, Kafka, SQS
(LocalStack) and Service Bus (its emulator), in both directions (`dotnet publish interop/dotnet-probe -c Release -o interop/dotnet-probe/out`, then run
`./gradlew :interop:test` with `JFXMAP_DOTNET_PROBE="dotnet $PWD/interop/dotnet-probe/out/DotnetProbe.dll"`).

## Validation

`jfxmap-validation` checks expressions before the first mapping does, in two opt-in ways.

**At compile time**, the javac plugin `JfxMapExpressions` (FxMap.Analyzers' `OFX001`) parses the expression given to
`map(target, "...")`, `expression("...")` and `otherwise("...")` when it is a string literal or a compile-time
constant, and reports one that does not parse as a compile error on the literal:

```
OrderProfile.java:15: error: OFX001: Expression 'Country{Name}' is invalid: Projection requires '.' before '{' at position 7
```

The calls are matched by the method javac resolves, so `Stream.map(...)` or another class's `map(x, "...")` is never
reported. Null, blank and non-constant expressions are not checked. Put the module on the annotation processor path
and pass `-Xplugin:JfxMapExpressions` (`-Xplugin:JfxMapExpressions warn` makes it a warning, which `-Werror` still
turns into a failure). Gradle:

```kotlin
dependencies {
    annotationProcessor("io.github.quyvu01:jfxmap-validation:0.1.0-SNAPSHOT")
}

tasks.compileJava {
    options.compilerArgs.add("-Xplugin:JfxMapExpressions")
}
```

Maven:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.quyvu01</groupId>
                <artifactId>jfxmap-validation</artifactId>
                <version>0.1.0-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Xplugin:JfxMapExpressions</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

On the processor path the plugin needs only `jfxmap-expression`. [samples/order-service](samples/order-service/build.gradle.kts)
compiles with it.

**At startup**, `JfxMapValidator` checks what the plugin cannot see, such as an expression built from configuration:

```java
List<ValidationProblem> problems = JfxMapValidator.validate(profiles);  // ProfileOf<?>... or an Iterable
JfxMapValidator.validateEntities(entityConfigurations);                 // EntityConfigureOf<?>...
JfxMapValidator.validateOrThrow(profiles, entityConfigurations);        // JfxMapValidationException with every problem
```

A profile is checked by declaring its rules, planning them as the mapper does (readable keys, writable targets, no
cycle) and parsing each fixed expression (the functions of a conditional rule are not called). An entity
configuration is checked by running its `configure()` and checking that each exposed name is a plain name an
expression can use. Each `ValidationProblem` names the class, the property, the expression and what is wrong. Nothing
runs it by itself: the Java parser may reject a few constructs that FxMap's parser accepts, so call it where it fits,
in a test or at startup.

## Build

Java 21 and the Gradle wrapper:

```sh
./gradlew build
```

The integration tests start MongoDB, NATS, RabbitMQ, Kafka, LocalStack and the Service Bus emulator with
Testcontainers, so they need Docker.

## BOM

`io.github.quyvu01:jfxmap-bom` holds the version of every jfxmap module, so the modules are declared without one.
Gradle:

```kotlin
dependencies {
    implementation(platform("io.github.quyvu01:jfxmap-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.quyvu01:jfxmap-spring-boot-starter")
    implementation("io.github.quyvu01:jfxmap-grpc")
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.quyvu01</groupId>
      <artifactId>jfxmap-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

The modules are not on Maven Central yet: `./gradlew publishToMavenLocal` puts them in `~/.m2`, and
`./gradlew publishAllPublicationsToStagingRepository` lays out the bundle for the Central Portal in
`build/staging-deploy` (signed when `ORG_GRADLE_PROJECT_signingInMemoryKey` and
`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` are set). Every `jfxmap-*` project is published, and listed in the
BOM, without a line of its own; `./gradlew build` checks the generated POMs (`:jfxmap-bom:verifyPoms`).

## Benchmarks

[benchmarks/](benchmarks/) has JMH benchmarks of the expression parser (simple, medium and complex expressions), of
in-memory mapping (`DistributedMapper` over `LocalRequestClient` and `InMemoryQueryHandler`, 1, 100 and 10,000 DTOs
with navigations, an aggregation, a chained rule and nested DTOs) and of the JSON wire messages. `./gradlew build`
compiles them and runs their test, not the benchmarks:

```sh
./gradlew :benchmarks:jmhJar
java -jar benchmarks/build/libs/benchmarks-jmh.jar                     # all, with the default iterations
java -jar benchmarks/build/libs/benchmarks-jmh.jar MappingBenchmark -p dtos=100 -wi 1 -i 3 -f 1   # one, briefly
./gradlew :benchmarks:jmh -PjmhIncludes=ExpressionParser               # or through Gradle
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
