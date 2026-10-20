package io.github.quyvu01.jfxmap.spring;

import com.mongodb.client.MongoCollection;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.grpc.GrpcRequestClient;
import io.github.quyvu01.jfxmap.jpa.JpaQueryHandler;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaServer;
import io.github.quyvu01.jfxmap.mongodb.MongoQueryHandler;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.IOException;

import java.util.List;

/**
 * Wires jfxmap into a Spring Boot application (FxMap's {@code AddFxMap(...)}).
 *
 * <ul>
 *   <li>Every {@link ProfileOf} bean is registered in the {@link DistributedMapper} bean.</li>
 *   <li>Every {@link EntityConfigureOf} bean gets a {@link QueryHandler}: a {@code QueryHandler} bean for its key if
 *       there is one, else a {@link JpaQueryHandler} when jfxmap-jpa is on the classpath and the entity is in the
 *       persistence unit.</li>
 *   <li>With jfxmap-grpc on the classpath, the owned keys are served by a {@link FxMapGrpcService} bean (started on
 *       {@code jfxmap.grpc.server.port} when it is set), and {@code jfxmap.grpc.client.hosts} creates the
 *       {@link GrpcRequestClient} for the keys of other services.</li>
 *   <li>With jfxmap-nats, {@code jfxmap.nats.url} (or a NATS {@code Connection} bean) serves the owned keys with a
 *       {@link NatsServer} and sends the others with a {@link NatsRequestClient}; with jfxmap-rabbitmq,
 *       {@code jfxmap.rabbitmq.host} does the same over RabbitMQ, and with jfxmap-kafka,
 *       {@code jfxmap.kafka.bootstrap-servers} over Kafka. {@code jfxmap.<transport>.server-enabled=false} keeps
 *       only the client.</li>
 *   <li>With jfxmap-mongodb, an {@link EntityConfigureOf} bean whose entity is the document class of a
 *       {@code MongoCollection} bean gets a {@link MongoQueryHandler}.</li>
 * </ul>
 *
 * A {@link RequestClient} bean of the application replaces the transports' clients; the owned keys are still
 * answered in process. Configure one transport for the client side: the first one configured (gRPC, NATS, RabbitMQ,
 * Kafka) sends the requests.
 */
@AutoConfiguration
@EnableConfigurationProperties(JfxMapProperties.class)
public class JfxMapAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public JfxMapQueryHandlers jfxMapQueryHandlers(ObjectProvider<QueryHandler> handlers,
                                                   ObjectProvider<EntityConfigureOf<?>> configs,
                                                   ObjectProvider<EntityQueryHandlerFactory> factories) {
        return new JfxMapQueryHandlers(handlers.orderedStream().toList(), configs.orderedStream().toList(),
                factories.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedMapper jfxMapDistributedMapper(JfxMapProperties properties, JfxMapQueryHandlers handlers,
                                                     ObjectProvider<ProfileOf<?>> profiles,
                                                     ObjectProvider<RequestClient> remote, BeanFactory beanFactory) {
        var builder = FxMap.builder()
                .addProfiles(profiles.orderedStream().toArray(ProfileOf<?>[]::new))
                .requestClient(new JfxMapRequestClient(handlers, remote.getIfUnique()))
                .maxNestingDepth(properties.getMaxNestingDepth())
                .services(type -> beanFactory.getBeanProvider(type).getIfAvailable());
        if (properties.isThrowIfException()) builder.throwIfException();
        return builder.build();
    }

    /** jfxmap-jpa: entities of the application's persistence unit. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JpaQueryHandler.class, EntityManagerFactory.class})
    static class JpaConfiguration {
        @Bean
        public EntityQueryHandlerFactory jfxMapJpaQueryHandlerFactory(ObjectProvider<EntityManagerFactory> factories) {
            return config -> factories.orderedStream()
                    .filter(factory -> JpaQueryHandler.isManaged(factory, config.entityType()))
                    .findFirst()
                    .map(factory -> new JpaQueryHandler<>(config, factory));
        }
    }

    /** jfxmap-grpc: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(FxMapGrpcService.class)
    static class GrpcConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public FxMapGrpcService jfxMapGrpcService(JfxMapQueryHandlers handlers) {
            return new FxMapGrpcService(List.copyOf(handlers.all()));
        }

        @Bean
        @ConditionalOnProperty("jfxmap.grpc.server.port")
        public JfxMapGrpcServer jfxMapGrpcServer(FxMapGrpcService service, JfxMapProperties properties) {
            return new JfxMapGrpcServer(service, properties.getGrpc().getServer().getPort());
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RequestClient.class)
        @Conditional(OnGrpcHosts.class)
        public GrpcRequestClient jfxMapGrpcRequestClient(JfxMapProperties properties) {
            var client = properties.getGrpc().getClient();
            return GrpcRequestClient.builder()
                    .hosts(client.getHosts().stream().filter(host -> !host.isBlank()).toList())
                    .timeout(client.getTimeout())
                    .build();
        }
    }

    /** jfxmap-mongodb: the collections of the application, as {@code MongoCollection} beans. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MongoQueryHandler.class, MongoCollection.class})
    static class MongoConfiguration {
        @Bean
        public EntityQueryHandlerFactory jfxMapMongoQueryHandlerFactory(ObjectProvider<MongoCollection<?>> collections) {
            return config -> collections.orderedStream()
                    .filter(collection -> collection.getDocumentClass() == config.entityType())
                    .findFirst()
                    .map(collection -> new MongoQueryHandler<>(config, collection));
        }
    }

    /** jfxmap-nats: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(NatsServer.class)
    static class NatsConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(Connection.class)
        @ConditionalOnProperty("jfxmap.nats.url")
        public Connection jfxMapNatsConnection(JfxMapProperties properties)
                throws IOException, InterruptedException {
            return Nats.connect(new Options.Builder().server(properties.getNats().getUrl())
                    .connectionName("jfxmap").build());
        }

        @Bean
        @ConditionalOnBean(Connection.class)
        @ConditionalOnProperty(value = "jfxmap.nats.server-enabled", matchIfMissing = true)
        public JfxMapTransportServer jfxMapNatsServer(Connection connection,
                                                      JfxMapQueryHandlers handlers, JfxMapProperties properties) {
            var nats = properties.getNats();
            var server = NatsServer.builder(connection, handlers.all()).topicPrefix(nats.getTopicPrefix())
                    .timeout(nats.getTimeout()).maxConcurrentProcessing(nats.getMaxConcurrentProcessing()).build();
            return new JfxMapTransportServer("nats", server::start, server::close, server::isRunning);
        }

        @Bean
        @ConditionalOnBean(Connection.class)
        @ConditionalOnMissingBean(RequestClient.class)
        public NatsRequestClient jfxMapNatsRequestClient(Connection connection,
                                                         JfxMapProperties properties) {
            return NatsRequestClient.builder(connection).topicPrefix(properties.getNats().getTopicPrefix())
                    .timeout(properties.getNats().getTimeout()).build();
        }
    }

    /** jfxmap-rabbitmq: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitMqServer.class)
    @ConditionalOnProperty("jfxmap.rabbitmq.host")
    static class RabbitMqConfiguration {
        @Bean(destroyMethod = "close")
        public JfxMapRabbitMqConnection jfxMapRabbitMqConnection(JfxMapProperties properties) {
            return JfxMapRabbitMqConnection.open(properties.getRabbitmq());
        }

        @Bean
        @ConditionalOnProperty(value = "jfxmap.rabbitmq.server-enabled", matchIfMissing = true)
        public JfxMapTransportServer jfxMapRabbitMqServer(JfxMapRabbitMqConnection connection,
                                                          JfxMapQueryHandlers handlers, JfxMapProperties properties,
                                                          Environment environment) {
            var rabbitmq = properties.getRabbitmq();
            var server = RabbitMqServer.builder(connection.connection(),
                            applicationName(properties, environment, null), handlers.all())
                    .timeout(rabbitmq.getTimeout()).channelPoolSize(rabbitmq.getChannelPoolSize())
                    .maxConcurrentProcessing(rabbitmq.getMaxConcurrentProcessing()).build();
            return new JfxMapTransportServer("rabbitmq", server::start, server::close, server::isRunning);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RequestClient.class)
        public RabbitMqRequestClient jfxMapRabbitMqRequestClient(JfxMapRabbitMqConnection connection,
                                                                 JfxMapProperties properties) {
            return RabbitMqRequestClient.builder(connection.connection())
                    .timeout(properties.getRabbitmq().getTimeout()).build();
        }
    }

    /** jfxmap-kafka: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaServer.class)
    @ConditionalOnProperty("jfxmap.kafka.bootstrap-servers")
    static class KafkaConfiguration {
        @Bean
        @ConditionalOnProperty(value = "jfxmap.kafka.server-enabled", matchIfMissing = true)
        public JfxMapTransportServer jfxMapKafkaServer(JfxMapQueryHandlers handlers, JfxMapProperties properties) {
            var kafka = properties.getKafka();
            var builder = KafkaServer.builder(kafka.getBootstrapServers(), handlers.all())
                    .timeout(kafka.getTimeout()).partitions(kafka.getPartitions())
                    .replicationFactor(kafka.getReplicationFactor())
                    .maxConcurrentProcessing(kafka.getMaxConcurrentProcessing());
            kafka.getProperties().forEach(builder::property);
            var server = builder.build();
            return new JfxMapTransportServer("kafka", server::start, server::close, server::isRunning);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RequestClient.class)
        public KafkaRequestClient jfxMapKafkaRequestClient(JfxMapProperties properties, Environment environment) {
            var kafka = properties.getKafka();
            var builder = KafkaRequestClient.builder(kafka.getBootstrapServers(),
                            applicationName(properties, environment, "jfxmap"))
                    .timeout(kafka.getTimeout()).replicationFactor(kafka.getReplicationFactor());
            kafka.getProperties().forEach(builder::property);
            return builder.build();
        }
    }

    /**
     * {@code jfxmap.application-name}, else {@code spring.application.name}, else the fallback.
     *
     * @throws IllegalStateException when there is none and no fallback
     */
    static String applicationName(JfxMapProperties properties, Environment environment, String fallback) {
        var name = properties.getApplicationName();
        if (name == null || name.isBlank()) name = environment.getProperty("spring.application.name");
        if (name == null || name.isBlank()) name = fallback;
        if (name == null)
            throw new IllegalStateException("Set jfxmap.application-name or spring.application.name: it names the "
                                            + "RabbitMQ queue of this service, which must differ between services.");
        return name;
    }

    /** Whether {@code jfxmap.grpc.client.hosts} lists a host, written as a list or comma-separated. */
    static final class OnGrpcHosts extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var hosts = Binder.get(context.getEnvironment())
                    .bind("jfxmap.grpc.client.hosts", Bindable.listOf(String.class))
                    .orElse(List.of())
                    .stream().filter(host -> !host.isBlank()).toList();
            return hosts.isEmpty()
                    ? ConditionOutcome.noMatch("jfxmap.grpc.client.hosts lists no host")
                    : ConditionOutcome.match("jfxmap.grpc.client.hosts lists " + hosts);
        }
    }

}
