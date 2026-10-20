package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.grpc.GrpcRequestClient;
import io.github.quyvu01.jfxmap.jpa.JpaQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
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
import org.springframework.core.type.AnnotatedTypeMetadata;

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
 * </ul>
 *
 * A {@link RequestClient} bean of the application replaces the gRPC client; the owned keys are still answered in
 * process.
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
