package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.grpc.GrpcRequestClient;
import io.github.quyvu01.jfxmap.jpa.JpaQueryHandler;
import io.github.quyvu01.jfxmap.mongodb.MongoQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.spring.Fixtures.EmailVisibility;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDto;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDtoProfile;
import io.github.quyvu01.jfxmap.spring.Fixtures.UserConfig;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfxMapAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    private static List<OrderDto> mapOrders(DistributedMapper mapper) {
        var orders = List.of(new OrderDto(1), new OrderDto(2));
        mapper.map(orders);
        return orders;
    }

    @Test
    void answersFromAQueryHandlerBeanInProcess() {
        runner.withBean(OrderDtoProfile.class)
                .withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                .withPropertyValues("jfxmap.throw-if-exception=true")
                .run(context -> {
                    var orders = mapOrders(context.getBean(DistributedMapper.class));

                    assertThat(orders.get(0).userName).isEqualTo("Alice (memory)");
                    assertThat(orders.get(0).userEmail).isEqualTo("alice@memory");
                    assertThat(orders.get(1).userName).isNull();
                    assertThat(context).doesNotHaveBean(GrpcRequestClient.class)
                            .doesNotHaveBean(JfxMapGrpcServer.class)
                            .hasSingleBean(FxMapGrpcService.class);
                });
    }

    @Test
    void givesConditionalExpressionsTheApplicationsBeans() {
        runner.withBean(OrderDtoProfile.class)
                .withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .run(context -> assertThat(mapOrders(context.getBean(DistributedMapper.class)).get(0).userEmail)
                        .isEqualTo("hidden"));
    }

    @Test
    void createsAJpaHandlerForAnEntityOfThePersistenceUnit() {
        runner.withBean(OrderDtoProfile.class)
                .withBean(UserConfig.class)
                .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                .withBean(EntityManagerFactory.class, () -> Fixtures.usersDatabase("starter-jpa"))
                .run(context -> {
                    var orders = mapOrders(context.getBean(DistributedMapper.class));

                    assertThat(orders).extracting(o -> o.userName).containsExactly("Alice", "Bob");
                    assertThat(orders).extracting(o -> o.userEmail)
                            .containsExactly("alice@example.com", "bob@example.com");
                    assertThat(context.getBean(JfxMapQueryHandlers.class).get(DistributedKeyRef.of("UserOf")))
                            .isInstanceOf(JpaQueryHandler.class);
                });
    }

    @Test
    void prefersAQueryHandlerBeanToTheDataProvider() {
        runner.withBean(UserConfig.class)
                .withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withBean(EntityManagerFactory.class, () -> Fixtures.usersDatabase("starter-prefers"))
                .run(context -> assertThat(context.getBean(JfxMapQueryHandlers.class).all())
                        .singleElement().isNotInstanceOf(JpaQueryHandler.class));
    }

    @Test
    void leavesAnEntityNoProviderHoldsUnanswered() {
        runner.withBean(UserConfig.class)
                .run(context -> assertThat(context.getBean(JfxMapQueryHandlers.class).all()).isEmpty());
        runner.withBean(UserConfig.class)
                .withClassLoader(new FilteredClassLoader(JpaQueryHandler.class, MongoQueryHandler.class))
                .withBean(EntityManagerFactory.class, () -> Fixtures.usersDatabase("starter-no-jpa"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EntityQueryHandlerFactory.class);
                    assertThat(context.getBean(JfxMapQueryHandlers.class).all()).isEmpty();
                });
    }

    @Test
    void failsOnTwoHandlersForOneKey() {
        runner.withBean("first", QueryHandler.class, Fixtures::inMemoryUsers)
                .withBean("second", QueryHandler.class, Fixtures::inMemoryUsers)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class));
    }

    @Test
    void sendsTheOtherKeysToTheApplicationsRequestClient() {
        RequestClient remote = (key, request, context) -> CompletableFuture.failedFuture(
                new IllegalStateException("remote " + key));
        runner.withBean(OrderDtoProfile.class)
                .withBean(RequestClient.class, () -> remote)
                .withPropertyValues("jfxmap.throw-if-exception=true", "jfxmap.grpc.client.hosts=http://localhost:1")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GrpcRequestClient.class);
                    assertThatThrownBy(() -> mapOrders(context.getBean(DistributedMapper.class)))
                            .hasMessageContaining("remote UserOf");
                });
    }

    @Test
    void failsAKeyNobodyAnswersWhenAskedToThrow() {
        runner.withBean(OrderDtoProfile.class)
                .withPropertyValues("jfxmap.throw-if-exception=true", "jfxmap.max-nesting-depth=4")
                .run(context -> {
                    assertThat(context.getBean(JfxMapProperties.class).getMaxNestingDepth()).isEqualTo(4);
                    assertThatThrownBy(() -> mapOrders(context.getBean(DistributedMapper.class)))
                            .isInstanceOf(DistributedMapException.class);
                });
        runner.withBean(OrderDtoProfile.class)
                .run(context -> assertThat(mapOrders(context.getBean(DistributedMapper.class)).get(0).userName)
                        .isNull());
    }

    @Test
    void createsTheGrpcClientFromItsHosts() {
        runner.withPropertyValues("jfxmap.grpc.client.hosts=http://users:9090, http://products:9090",
                        "jfxmap.grpc.client.timeout=5s")
                .run(context -> assertThat(context.getBean(GrpcRequestClient.class).hosts())
                        .containsExactly("http://users:9090", "http://products:9090"));
        runner.withPropertyValues("jfxmap.grpc.client.hosts[0]=http://users:9090", "jfxmap.grpc.client.hosts[1]= ")
                .run(context -> assertThat(context.getBean(GrpcRequestClient.class).hosts())
                        .containsExactly("http://users:9090"));
        runner.withPropertyValues("jfxmap.grpc.client.hosts=")
                .run(context -> assertThat(context).doesNotHaveBean(GrpcRequestClient.class));
    }

    @Test
    void leavesGrpcOutWithoutJfxmapGrpc() {
        runner.withClassLoader(new FilteredClassLoader(FxMapGrpcService.class))
                .withPropertyValues("jfxmap.grpc.client.hosts=http://users:9090", "jfxmap.grpc.server.port=0")
                .run(context -> assertThat(context).doesNotHaveBean(FxMapGrpcService.class)
                        .doesNotHaveBean(GrpcRequestClient.class).doesNotHaveBean(JfxMapGrpcServer.class));
    }

    @Test
    void mapsAcrossTwoApplicationsOverGrpc() {
        runner.withBean(UserConfig.class)
                .withBean(EntityManagerFactory.class, () -> Fixtures.usersDatabase("starter-owner"))
                .withPropertyValues("jfxmap.grpc.server.port=0")
                .run(owner -> {
                    var port = owner.getBean(JfxMapGrpcServer.class).getPort();
                    assertThat(port).isPositive();

                    runner.withBean(OrderDtoProfile.class)
                            .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                            .withPropertyValues("jfxmap.throw-if-exception=true",
                                    "jfxmap.grpc.client.hosts=http://localhost:" + port)
                            .run(consumer -> {
                                var orders = mapOrders(consumer.getBean(DistributedMapper.class));

                                assertThat(orders).extracting(o -> o.userName).containsExactly("Alice", "Bob");
                                assertThat(orders.get(1).userEmail).isEqualTo("bob@example.com");
                            });
                });
    }

    @Test
    void keepsTheApplicationsOwnBeans() {
        var handlers = new JfxMapQueryHandlers(List.of(), List.of(), List.of());
        DistributedMapper mapper = new DistributedMapper() {
            @Override
            public CompletableFuture<Void> mapAsync(Object value, java.util.Map<String, String> headers) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<ItemsResponse> fetchAsync(DistributedKeyRef key,
                                                               io.github.quyvu01.jfxmap.response.MapRequest request,
                                                               java.util.Map<String, String> headers) {
                return CompletableFuture.completedFuture(ItemsResponse.empty());
            }
        };
        runner.withBean(JfxMapQueryHandlers.class, () -> handlers)
                .withBean(DistributedMapper.class, () -> mapper)
                .run(context -> {
                    assertThat(context.getBean(JfxMapQueryHandlers.class)).isSameAs(handlers);
                    assertThat(context.getBean(DistributedMapper.class)).isSameAs(mapper);
                });
    }
}
