package io.github.quyvu01.jfxmap.spring;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.mongodb.MongoQueryHandler;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.spring.Fixtures.EmailVisibility;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDto;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDtoProfile;
import org.bson.codecs.pojo.annotations.BsonId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Two applications that map across each other over each transport, and MongoDB as a data provider. */
@Testcontainers
class JfxMapTransportsIntegrationTest {
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    private static List<OrderDto> mapOrders(DistributedMapper mapper) {
        var orders = List.of(new OrderDto(1), new OrderDto(2));
        mapper.map(orders);
        return orders;
    }

    /** The owner of the users answers over the transport; the consumer maps its orders over it. */
    private void mapsAcross(Class<?> clientType, String... transport) {
        runner.withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withPropertyValues(transport)
                .withPropertyValues("spring.application.name=UserService")
                .run(owner -> {
                    assertThat(owner).hasNotFailed().hasSingleBean(JfxMapTransportServer.class);
                    assertThat(owner.getBean(JfxMapTransportServer.class).isRunning()).isTrue();

                    runner.withBean(OrderDtoProfile.class)
                            .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                            .withPropertyValues(transport)
                            .withPropertyValues("spring.application.name=OrderService",
                                    "jfxmap.throw-if-exception=true")
                            .run(consumer -> {
                                assertThat(consumer).hasNotFailed().hasSingleBean(clientType);
                                var orders = mapOrders(consumer.getBean(DistributedMapper.class));

                                assertThat(orders).extracting(o -> o.userName).containsExactly("Alice (memory)", null);
                                assertThat(orders.getFirst().userEmail).isEqualTo("alice@memory");
                            });
                });
    }

    @Test
    void mapsAcrossTwoApplicationsOverNats() {
        mapsAcross(NatsRequestClient.class, "jfxmap.nats.url=nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }

    @Test
    void mapsAcrossTwoApplicationsOverRabbitMq() {
        mapsAcross(RabbitMqRequestClient.class, "jfxmap.rabbitmq.host=" + RABBIT.getHost(),
                "jfxmap.rabbitmq.port=" + RABBIT.getAmqpPort(),
                "jfxmap.rabbitmq.username=" + RABBIT.getAdminUsername(),
                "jfxmap.rabbitmq.password=" + RABBIT.getAdminPassword());
    }

    @Test
    void mapsAcrossTwoApplicationsOverKafka() {
        mapsAcross(KafkaRequestClient.class, "jfxmap.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    }

    @Test
    void aRabbitMqServerNeedsAnApplicationName() {
        runner.withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withPropertyValues("jfxmap.rabbitmq.host=" + RABBIT.getHost(),
                        "jfxmap.rabbitmq.port=" + RABBIT.getAmqpPort(),
                        "jfxmap.rabbitmq.username=" + RABBIT.getAdminUsername(),
                        "jfxmap.rabbitmq.password=" + RABBIT.getAdminPassword())
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Set jfxmap.application-name or spring.application.name"));
    }

    public record MongoUser(@BsonId long id, String name, String email) {
    }

    public static class MongoUserConfig extends EntityConfigureOf<MongoUser> {
        @Override
        protected void configure() {
            id(MongoUser::id);
            defaultProperty(MongoUser::name);
            useDistributedKey("UserOf");
            exposedName(MongoUser::email, "UserEmail");
        }
    }

    @Test
    void createsAMongoHandlerForTheCollectionOfAnEntity() {
        try (MongoClient client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO.getReplicaSetUrl())).build())) {
            var users = client.getDatabase("starter").getCollection("users", MongoUser.class);
            users.drop();
            users.insertMany(List.of(new MongoUser(1, "Alice (mongo)", "alice@mongo"),
                    new MongoUser(2, "Bob (mongo)", "bob@mongo")));

            runner.withBean(OrderDtoProfile.class)
                    .withBean(MongoUserConfig.class)
                    .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                    .withBean("users", MongoCollection.class, () -> users)
                    .withPropertyValues("jfxmap.throw-if-exception=true", "jfxmap.telemetry.enabled=false")
                    .run(context -> {
                        assertThat(context.getBean(JfxMapQueryHandlers.class).get(DistributedKeyRef.of("UserOf")))
                                .isInstanceOf(MongoQueryHandler.class);
                        var orders = mapOrders(context.getBean(DistributedMapper.class));

                        assertThat(orders).extracting(o -> o.userName).containsExactly("Alice (mongo)", "Bob (mongo)");
                        assertThat(orders.get(1).userEmail).isEqualTo("bob@mongo");
                    });
        }
    }
}
