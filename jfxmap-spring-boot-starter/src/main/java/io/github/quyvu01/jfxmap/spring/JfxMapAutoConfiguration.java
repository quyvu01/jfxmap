package io.github.quyvu01.jfxmap.spring;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder;
import com.mongodb.client.MongoCollection;
import graphql.GraphQLContext;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusRequestClient;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusServer;
import io.github.quyvu01.jfxmap.graphql.JfxMapGraphQl;
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
import io.github.quyvu01.jfxmap.opentelemetry.JfxMapTelemetry;
import io.github.quyvu01.jfxmap.pipeline.ReceiveBehavior;
import io.github.quyvu01.jfxmap.pipeline.RetryPolicy;
import io.github.quyvu01.jfxmap.pipeline.SendBehavior;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import io.github.quyvu01.jfxmap.sqs.SqsRequestClient;
import io.github.quyvu01.jfxmap.sqs.SqsServer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
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
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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
 *   <li>With jfxmap-sqs, {@code jfxmap.sqs.region} (or an {@code SqsClient} bean) does the same over Amazon SQS, and
 *       with jfxmap-azure-servicebus, {@code jfxmap.servicebus.connection-string} over Azure Service Bus.</li>
 *   <li>With jfxmap-graphql and Spring for GraphQL, the fields of the GraphQL types named after profiled classes are
 *       resolved through the mapper, batched per request ({@link JfxMapGraphQl}).</li>
 *   <li>With jfxmap-mongodb, an {@link EntityConfigureOf} bean whose entity is the document class of a
 *       {@code MongoCollection} bean gets a {@link MongoQueryHandler}.</li>
 * </ul>
 *
 * <p>Around the requests: the {@link SendBehavior} beans and the retry policy ({@code jfxmap.retry.*}, or a
 * {@link RetryPolicy} bean) on the client side, the {@link ReceiveBehavior} beans around each handler; with
 * jfxmap-opentelemetry, spans and metrics on both sides ({@code jfxmap.telemetry.enabled=false} turns them off). The
 * transport servers run under a supervisor that starts them again when they fail ({@code jfxmap.supervisor.*}).
 *
 * <p>A {@link RequestClient} bean of the application replaces the transports' clients; the owned keys are still
 * answered in process. Configure one transport for the client side: the first one configured (gRPC, NATS, RabbitMQ,
 * Kafka, SQS, Service Bus) sends the requests.
 */
@AutoConfiguration
@EnableConfigurationProperties(JfxMapProperties.class)
public class JfxMapAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public JfxMapQueryHandlers jfxMapQueryHandlers(ObjectProvider<QueryHandler> handlers,
                                                   ObjectProvider<EntityConfigureOf<?>> configs,
                                                   ObjectProvider<EntityQueryHandlerFactory> factories,
                                                   ObjectProvider<ReceiveBehavior> behaviors,
                                                   ObjectProvider<JfxMapInstrumentation> instrumentation) {
        var instrument = instrumentation.getIfUnique();
        return new JfxMapQueryHandlers(handlers.orderedStream().toList(), configs.orderedStream().toList(),
                factories.orderedStream().toList(), behaviors.orderedStream().toList(),
                instrument == null ? UnaryOperator.identity() : instrument::instrument);
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedMapper jfxMapDistributedMapper(JfxMapProperties properties, JfxMapQueryHandlers handlers,
                                                     ObjectProvider<ProfileOf<?>> profiles,
                                                     ObjectProvider<RequestClient> remote,
                                                     ObjectProvider<SendBehavior> behaviors,
                                                     ObjectProvider<RetryPolicy> retryPolicy,
                                                     ObjectProvider<JfxMapInstrumentation> instrumentation,
                                                     BeanFactory beanFactory) {
        var client = remote.getIfUnique();
        var instrument = instrumentation.getIfUnique();
        if (client != null && instrument != null) client = instrument.instrument(client);
        var builder = FxMap.builder()
                .addProfiles(profiles.orderedStream().toArray(ProfileOf<?>[]::new))
                .requestClient(new JfxMapRequestClient(handlers, client))
                .addSendBehaviors(behaviors.orderedStream().toArray(SendBehavior[]::new))
                .maxNestingDepth(properties.getMaxNestingDepth())
                .services(type -> beanFactory.getBeanProvider(type).getIfAvailable());
        var retry = retryPolicy.getIfUnique(() -> properties.getRetry().toPolicy());
        if (retry != null) builder.retryPolicy(retry);
        if (properties.isThrowIfException()) builder.throwIfException();
        return builder.build();
    }

    /** jfxmap-opentelemetry: traces and measures the requests with the application's OpenTelemetry. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JfxMapTelemetry.class, OpenTelemetry.class})
    @ConditionalOnProperty(value = "jfxmap.telemetry.enabled", matchIfMissing = true)
    static class TelemetryConfiguration {
        /**
         * Backed by the application's {@code OpenTelemetry} bean, else by the global one (the Java agent's), which
         * records nothing when no SDK is set up.
         */
        @Bean
        @ConditionalOnMissingBean
        public JfxMapTelemetry jfxMapTelemetry(ObjectProvider<OpenTelemetry> openTelemetry) {
            return JfxMapTelemetry.create(openTelemetry.getIfUnique(GlobalOpenTelemetry::get));
        }

        @Bean
        @ConditionalOnMissingBean
        public JfxMapInstrumentation jfxMapInstrumentation(JfxMapTelemetry telemetry) {
            return new JfxMapInstrumentation() {
                @Override
                public RequestClient instrument(RequestClient client) {
                    return telemetry.instrument(client);
                }

                @Override
                public QueryHandler instrument(QueryHandler handler) {
                    return telemetry.instrument(handler);
                }
            };
        }
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
            return new JfxMapTransportServer("nats", server, properties.getSupervisor().toOptions());
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
            return new JfxMapTransportServer("rabbitmq", server, properties.getSupervisor().toOptions());
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
            return new JfxMapTransportServer("kafka", server, properties.getSupervisor().toOptions());
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

    /** jfxmap-sqs: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SqsServer.class)
    static class SqsConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(SqsClient.class)
        @ConditionalOnProperty("jfxmap.sqs.region")
        public SqsClient jfxMapSqsClient(JfxMapProperties properties) {
            var sqs = properties.getSqs();
            var builder = SqsClient.builder().region(Region.of(sqs.getRegion()));
            if (sqs.getEndpoint() != null && !sqs.getEndpoint().isBlank())
                builder.endpointOverride(URI.create(sqs.getEndpoint()));
            if (sqs.getAccessKeyId() != null && !sqs.getAccessKeyId().isBlank())
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(sqs.getAccessKeyId(), sqs.getSecretAccessKey())));
            return builder.build();
        }

        @Bean
        @ConditionalOnBean(SqsClient.class)
        @ConditionalOnProperty(value = "jfxmap.sqs.server-enabled", matchIfMissing = true)
        public JfxMapTransportServer jfxMapSqsServer(SqsClient sqsClient, JfxMapQueryHandlers handlers,
                                                     JfxMapProperties properties) {
            var sqs = properties.getSqs();
            var server = SqsServer.builder(sqsClient, handlers.all()).timeout(sqs.getTimeout())
                    .waitTime(sqs.getWaitTime()).maxConcurrentProcessing(sqs.getMaxConcurrentProcessing()).build();
            return new JfxMapTransportServer("sqs", server, properties.getSupervisor().toOptions());
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnBean(SqsClient.class)
        @ConditionalOnMissingBean(RequestClient.class)
        public SqsRequestClient jfxMapSqsRequestClient(SqsClient sqsClient, JfxMapProperties properties) {
            var sqs = properties.getSqs();
            return SqsRequestClient.builder(sqsClient).timeout(sqs.getTimeout()).waitTime(sqs.getWaitTime()).build();
        }
    }

    /** jfxmap-azure-servicebus: the server for the owned keys and the client for the others. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ServiceBusServer.class)
    @ConditionalOnProperty("jfxmap.servicebus.connection-string")
    static class ServiceBusConfiguration {
        @Bean
        public JfxMapServiceBusClients jfxMapServiceBusClients(JfxMapProperties properties) {
            var servicebus = properties.getServicebus();
            var builder = new ServiceBusClientBuilder().connectionString(servicebus.getConnectionString());
            if (servicebus.getTryTimeout() != null)
                builder.retryOptions(new AmqpRetryOptions().setTryTimeout(servicebus.getTryTimeout()));
            return new JfxMapServiceBusClients(builder);
        }

        @Bean
        @ConditionalOnProperty(value = "jfxmap.servicebus.server-enabled", matchIfMissing = true)
        public JfxMapTransportServer jfxMapServiceBusServer(JfxMapServiceBusClients clients,
                                                            JfxMapQueryHandlers handlers,
                                                            JfxMapProperties properties) {
            var servicebus = properties.getServicebus();
            var builder = ServiceBusServer.builder(clients.builder(), handlers.all())
                    .topicPrefix(servicebus.getTopicPrefix()).timeout(servicebus.getTimeout())
                    .maxConcurrentSessions(servicebus.getMaxConcurrentSessions());
            if (servicebus.isCreateQueues())
                builder.administration(new ServiceBusAdministrationClientBuilder()
                        .connectionString(servicebus.getConnectionString()).buildClient());
            var server = builder.build();
            return new JfxMapTransportServer("servicebus", server, properties.getSupervisor().toOptions());
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RequestClient.class)
        public ServiceBusRequestClient jfxMapServiceBusRequestClient(JfxMapServiceBusClients clients,
                                                                     JfxMapProperties properties) {
            var servicebus = properties.getServicebus();
            return ServiceBusRequestClient.builder(clients.builder()).topicPrefix(servicebus.getTopicPrefix())
                    .timeout(servicebus.getTimeout()).build();
        }
    }

    /** jfxmap-graphql with Spring for GraphQL: the mapped fields of the schema. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JfxMapGraphQl.class, RuntimeWiringConfigurer.class})
    static class GraphQlConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public JfxMapGraphQl jfxMapGraphQl(DistributedMapper mapper, JfxMapProperties properties) {
            var builder = JfxMapGraphQl.builder(mapper);
            properties.getGraphql().getTypeNames().forEach((className, typeName) -> {
                try {
                    builder.typeName(ClassUtils.forName(className, JfxMapAutoConfiguration.class.getClassLoader()),
                            typeName);
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new IllegalStateException("jfxmap.graphql.type-names names a class that cannot be loaded: "
                                                    + className, e);
                }
            });
            return builder.build();
        }

        /**
         * The data fetchers, and the data loader of each request: Spring for GraphQL gives a request a registry of data
         * loaders only when some are registered.
         */
        @Bean
        public RuntimeWiringConfigurer jfxMapRuntimeWiringConfigurer(JfxMapGraphQl graphQl,
                                                                     ObjectProvider<BatchLoaderRegistry> registries) {
            registries.ifAvailable(registry -> registry.<JfxMapGraphQl.FieldKey, Object>forName(
                            JfxMapGraphQl.DATA_LOADER)
                    .registerMappedBatchLoader((keys, environment) -> Mono.fromCompletionStage(
                            graphQl.load(keys, headers(environment.getContext())))));
            return graphQl::configure;
        }

        private static Map<String, String> headers(Object context) {
            if (context instanceof GraphQLContext graphQlContext) {
                Map<String, String> headers = graphQlContext.get(JfxMapGraphQl.HEADERS);
                if (headers != null) return headers;
            }
            return Map.of();
        }

        @Bean
        @ConditionalOnClass(WebGraphQlInterceptor.class)
        public WebGraphQlInterceptor jfxMapHeadersInterceptor(JfxMapProperties properties) {
            var forwarded = List.copyOf(properties.getGraphql().getForwardedHeaders());
            return (request, chain) -> {
                if (!forwarded.isEmpty()) {
                    var headers = new LinkedHashMap<String, String>();
                    for (var name : forwarded) {
                        var value = request.getHeaders().getFirst(name);
                        if (value != null) headers.put(name, value);
                    }
                    request.configureExecutionInput((input, builder) -> builder
                            .graphQLContext(context -> context.put(JfxMapGraphQl.HEADERS, headers)).build());
                }
                return chain.next(request);
            };
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
