package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.internal.ProfilePlan;

import java.util.LinkedHashMap;
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

        public DistributedMapper build() {
            if (requestClient == null && !plans.isEmpty()) throw new DistributedMapException.NoRequestClient();
            return new DefaultDistributedMapper(Map.copyOf(plans), requestClient, throwIfException, maxNestingDepth,
                    services);
        }
    }
}
