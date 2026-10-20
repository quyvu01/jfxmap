package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.internal.ProfilePlan;
import io.github.quyvu01.jfxmap.pipeline.Pipelines;
import io.github.quyvu01.jfxmap.pipeline.RetryPolicy;
import io.github.quyvu01.jfxmap.pipeline.SendBehavior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Entry point: registers the profiles and the request client, and builds the {@link DistributedMapper}.
 *
 * <pre>{@code
 * DistributedMapper mapper = FxMap.builder()
 *         .addProfiles(new UserSummaryProfile())
 *         .requestClient(natsClient)
 *         .retryPolicy(RetryPolicy.retries(3).delay(Duration.ofMillis(200)))
 *         .throwIfException()
 *         .build();
 *
 * mapper.map(dtos);
 * }</pre>
 */
public final class FxMap {
    public static final int DEFAULT_MAX_NESTING_DEPTH = 128;

    private FxMap() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Class<?>, ProfilePlan> plans = new LinkedHashMap<>();
        private RequestClient requestClient;
        private boolean throwIfException;
        private int maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH;
        private Function<Class<?>, Object> services;
        private final List<SendBehavior> sendBehaviors = new ArrayList<>();
        private RetryPolicy retryPolicy;

        private Builder() {
        }

        /**
         * Registers profiles. Their rules are checked now: a key or target property that does not exist, or a target
         * that cannot be written, fails here rather than while mapping.
         */
        public Builder addProfiles(ProfileOf<?>... profiles) {
            for (var profile : profiles) {
                var plan = ProfilePlan.of(profile);
                if (plans.putIfAbsent(plan.modelType(), plan) != null)
                    throw new DistributedMapException.AmbiguousProfileConfiguration(plan.modelType());
            }
            return this;
        }

        /** The transport that sends the requests. */
        public Builder requestClient(RequestClient requestClient) {
            this.requestClient = Objects.requireNonNull(requestClient, "requestClient");
            return this;
        }

        /**
         * Throws the failures (failed requests, expressions the owner could not evaluate, values that cannot be
         * written) instead of logging them and leaving the properties alone.
         */
        public Builder throwIfException() {
            this.throwIfException = true;
            return this;
        }

        /** How many levels of mapped objects inside mapped objects are followed (default 128). */
        public Builder maxNestingDepth(int maxNestingDepth) {
            if (maxNestingDepth < 1) throw new IllegalArgumentException("maxNestingDepth must be at least 1");
            this.maxNestingDepth = maxNestingDepth;
            return this;
        }

        /** Where conditional expressions find services, through {@link MapContext#service(Class)}. */
        public Builder services(Function<Class<?>, Object> services) {
            this.services = Objects.requireNonNull(services, "services");
            return this;
        }

        /**
         * Behaviors around each request sent (FxMap's {@code AddSendPipelines}), run in their order, inside the
         * retries: each attempt goes through them.
         */
        public Builder addSendBehaviors(SendBehavior... behaviors) {
            for (var behavior : behaviors) sendBehaviors.add(Objects.requireNonNull(behavior, "behavior"));
            return this;
        }

        /** Sends the failed requests again (FxMap's {@code SetRetryPolicy}); none by default. */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        public DistributedMapper build() {
            if (requestClient == null && !plans.isEmpty()) throw new DistributedMapException.NoRequestClient();
            // As in FxMap, the retries are outside the other behaviors
            var behaviors = new ArrayList<SendBehavior>();
            if (retryPolicy != null && retryPolicy.retries() > 0) behaviors.add(retryPolicy.asBehavior());
            behaviors.addAll(sendBehaviors);
            var client = requestClient == null ? null : Pipelines.send(requestClient, behaviors);
            return new DefaultDistributedMapper(Map.copyOf(plans), client, throwIfException, maxNestingDepth,
                    services);
        }
    }
}
