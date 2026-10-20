package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.opentelemetry.JfxMapTelemetry;
import io.github.quyvu01.jfxmap.opentelemetry.TelemetryNames;
import io.github.quyvu01.jfxmap.pipeline.ReceiveBehavior;
import io.github.quyvu01.jfxmap.pipeline.RetryPolicy;
import io.github.quyvu01.jfxmap.pipeline.SendBehavior;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.spring.Fixtures.EmailVisibility;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDto;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDtoProfile;
import io.github.quyvu01.jfxmap.supervision.SupervisionStrategy;
import io.github.quyvu01.jfxmap.supervision.SupervisorOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the starter puts around the requests: behaviors, retries, telemetry, and the supervisor's properties. */
class JfxMapPipelineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    private static List<OrderDto> mapOrders(DistributedMapper mapper) {
        var orders = List.of(new OrderDto(1), new OrderDto(2));
        mapper.map(orders);
        return orders;
    }

    /** A remote client that always fails, counting its calls. */
    private static RequestClient failing(AtomicInteger calls) {
        return (key, request, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("remote " + key));
        };
    }

    @Test
    void retriesTheRemoteRequestsAsTheRetryPropertiesSay() {
        var calls = new AtomicInteger();
        runner.withBean(OrderDtoProfile.class)
                .withBean(RequestClient.class, () -> failing(calls))
                .withPropertyValues("jfxmap.throw-if-exception=true", "jfxmap.retry.retries=2",
                        "jfxmap.retry.delay=1ms", "jfxmap.retry.max-delay=5ms")
                .run(context -> {
                    assertThatThrownBy(() -> mapOrders(context.getBean(DistributedMapper.class)))
                            .hasMessageContaining("remote UserOf");
                    assertThat(calls).hasValue(3);
                });
    }

    @Test
    void prefersARetryPolicyBeanToTheProperties() {
        var calls = new AtomicInteger();
        runner.withBean(OrderDtoProfile.class)
                .withBean(RequestClient.class, () -> failing(calls))
                .withBean(RetryPolicy.class, () -> RetryPolicy.retries(1))
                .withPropertyValues("jfxmap.throw-if-exception=true", "jfxmap.retry.retries=5")
                .run(context -> {
                    assertThatThrownBy(() -> mapOrders(context.getBean(DistributedMapper.class)));
                    assertThat(calls).hasValue(2);
                });
    }

    @Test
    void sendsEachRequestOnceWithoutRetries() {
        var calls = new AtomicInteger();
        runner.withBean(OrderDtoProfile.class)
                .withBean(RequestClient.class, () -> failing(calls))
                .withPropertyValues("jfxmap.throw-if-exception=true")
                .run(context -> {
                    assertThatThrownBy(() -> mapOrders(context.getBean(DistributedMapper.class)));
                    assertThat(calls).hasValue(1);
                });
    }

    @Test
    void runsTheBehaviorBeansAroundTheRequestsAndTheHandlers() {
        var sent = new CopyOnWriteArrayList<String>();
        var received = new CopyOnWriteArrayList<String>();
        SendBehavior send = (request, next) -> {
            sent.add(request.key().name());
            return next.proceed(request.withHeader("tenant", "acme"));
        };
        ReceiveBehavior receive = (request, next) -> {
            received.add(request.key().name() + " " + request.headers().get("tenant"));
            return next.proceed(request);
        };
        runner.withBean(OrderDtoProfile.class)
                .withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                .withBean(SendBehavior.class, () -> send)
                .withBean(ReceiveBehavior.class, () -> receive)
                .withPropertyValues("jfxmap.throw-if-exception=true")
                .run(context -> {
                    var orders = mapOrders(context.getBean(DistributedMapper.class));

                    assertThat(orders.get(0).userName).isEqualTo("Alice (memory)");
                    assertThat(sent).isNotEmpty().allMatch("UserOf"::equals);
                    assertThat(received).isNotEmpty().allMatch("UserOf acme"::equals);
                });
    }

    @Test
    void tracesTheRequestsWithTheApplicationsOpenTelemetry() {
        var spans = InMemorySpanExporter.create();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build();
        runner.withBean(OrderDtoProfile.class)
                .withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withBean(OpenTelemetry.class, () -> openTelemetry)
                .run(context -> {
                    assertThat(context).hasSingleBean(JfxMapTelemetry.class).hasSingleBean(JfxMapInstrumentation.class);
                    assertThat(mapOrders(context.getBean(DistributedMapper.class)).get(0).userName)
                            .isEqualTo("Alice (memory)");

                    assertThat(spans.getFinishedSpanItems()).isNotEmpty().allSatisfy(span -> {
                        assertThat(span.getName()).isEqualTo(TelemetryNames.SPAN_PROCESS);
                        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                        assertThat(span.getAttributes().get(TelemetryNames.DISTRIBUTED_KEY)).isEqualTo("UserOf");
                    });
                });
        openTelemetry.close();
    }

    @Test
    void tracesTheRemoteRequestsOncePerAttempt() {
        var spans = InMemorySpanExporter.create();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build();
        var calls = new AtomicInteger();
        runner.withBean(OrderDtoProfile.class)
                .withBean(RequestClient.class, () -> failing(calls))
                .withBean(OpenTelemetry.class, () -> openTelemetry)
                .withPropertyValues("jfxmap.retry.retries=1")
                .run(context -> {
                    mapOrders(context.getBean(DistributedMapper.class));

                    assertThat(calls).hasValue(2);
                    assertThat(spans.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> {
                        assertThat(span.getName()).isEqualTo(TelemetryNames.SPAN_REQUEST);
                        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
                        assertThat(span.getAttributes().get(TelemetryNames.TRANSPORT)).isEqualTo("custom");
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                    }).extracting(SpanData::getTraceId).doesNotHaveDuplicates();
                });
        openTelemetry.close();
    }

    @Test
    void leavesTelemetryOutWhenItIsTurnedOffOrMissing() {
        runner.withPropertyValues("jfxmap.telemetry.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JfxMapTelemetry.class)
                        .doesNotHaveBean(JfxMapInstrumentation.class));
        runner.withClassLoader(new FilteredClassLoader(JfxMapTelemetry.class))
                .run(context -> assertThat(context).doesNotHaveBean(JfxMapInstrumentation.class));
        runner.run(context -> assertThat(context).hasSingleBean(JfxMapTelemetry.class));
    }

    @Test
    void bindsTheRetryProperties() {
        var retry = new JfxMapProperties.Retry();
        assertThat(retry.toPolicy()).isNull();

        retry.setRetries(3);
        assertThat(retry.toPolicy().retries()).isEqualTo(3);
        assertThat(retry.toPolicy().delay()).isNull();

        retry.setDelay(Duration.ofMillis(100));
        assertThat(retry.toPolicy().delay().apply(3)).isEqualTo(Duration.ofMillis(100));

        retry.setMaxDelay(Duration.ofMillis(300));
        assertThat(retry.toPolicy().delay().apply(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(retry.toPolicy().delay().apply(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(retry.toPolicy().delay().apply(3)).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void bindsTheSupervisorPropertiesWithFxMapsDefaults() {
        assertThat(new JfxMapProperties().getSupervisor().toOptions()).isEqualTo(SupervisorOptions.defaults());

        runner.withPropertyValues("jfxmap.supervisor.strategy=one-for-all", "jfxmap.supervisor.max-restarts=5",
                        "jfxmap.supervisor.initial-backoff=2s", "jfxmap.supervisor.circuit-breaker=false")
                .run(context -> {
                    var options = context.getBean(JfxMapProperties.class).getSupervisor().toOptions();
                    assertThat(options.strategy()).isEqualTo(SupervisionStrategy.ONE_FOR_ALL);
                    assertThat(options.maxRestarts()).isEqualTo(5);
                    assertThat(options.initialBackoff()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(options.circuitBreaker()).isFalse();
                    assertThat(options.maxBackoff()).isEqualTo(SupervisorOptions.defaults().maxBackoff());
                });
        runner.withPropertyValues("jfxmap.supervisor.enabled=false")
                .run(context -> assertThat(context.getBean(JfxMapProperties.class).getSupervisor().toOptions())
                        .isNull());
    }
}
