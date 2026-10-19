package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.internal.ProfilePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ProfileOfTest {
    static final class UserOf implements DistributedKey {
    }

    public static class Dto {
        private Integer userId;
        private Integer countryId;
        private String userName;
        private String countryName;
        private final String readOnly = "";
        private List<Dto> children;

        public Integer getUserId() {
            return userId;
        }

        public Integer getCountryId() {
            return countryId;
        }

        public void setCountryId(Integer countryId) {
            this.countryId = countryId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getCountryName() {
            return countryName;
        }

        public void setCountryName(String countryName) {
            this.countryName = countryName;
        }

        public String getReadOnly() {
            return readOnly;
        }

        public List<Dto> getChildren() {
            return children;
        }
    }

    @Test
    void collectsTheRulesOfEachKey() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class)
                        .of(Dto::getUserId)
                        .map(Dto::getUserName)
                        .map(Dto::getCountryId, "CountryId");
                useDistributedKey("CountryOf")
                        .of(Dto::getCountryId)
                        .map(Dto::getCountryName, "Name");
            }
        };

        assertThat(profile.modelType()).isEqualTo(Dto.class);
        assertThat(profile.rules())
                .extracting(MappingRule::key, MappingRule::selectorProperty, MappingRule::targetProperty,
                        MappingRule::expression)
                .containsExactly(
                        tuple(DistributedKeyRef.of(UserOf.class), "userId", "userName", null),
                        tuple(DistributedKeyRef.of(UserOf.class), "userId", "countryId", "CountryId"),
                        tuple(DistributedKeyRef.of("CountryOf"), "countryId", "countryName", "Name"));
    }

    @Test
    void ordersTheRulesByTheirDependencies() {
        var plan = ProfilePlan.of(new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey("CountryOf").of(Dto::getCountryId).map(Dto::getCountryName, "Name");
                useDistributedKey(UserOf.class).of(Dto::getUserId).map(Dto::getCountryId, "CountryId");
            }
        });

        assertThat(plan.rules())
                .extracting(r -> r.target().name(), ProfilePlan.Rule::order)
                .containsExactlyInAnyOrder(tuple("countryName", 1), tuple("countryId", 0));
        // Rule properties are not walked; a collection of DTOs is
        assertThat(plan.walks()).extracting(p -> p.name()).containsExactly("children");
    }

    @Test
    void theLastRuleForAPropertyWins() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class).of(Dto::getUserId)
                        .map(Dto::getUserName, "First")
                        .map(Dto::getUserName, "Second");
            }
        };

        assertThat(profile.rules()).singleElement().extracting(MappingRule::expression).isEqualTo("Second");
    }

    @Test
    void rejectsALambdaSelector() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class).of(x -> x.getUserId() + 1);
            }
        };

        assertThatThrownBy(profile::rules).isInstanceOf(DistributedMapException.InvalidProfileSelector.class)
                .hasMessageContaining("method reference");
    }

    @Test
    void rejectsATargetThatCannotBeWritten() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class).of(Dto::getUserId).map(Dto::getReadOnly);
            }
        };

        assertThatThrownBy(() -> FxMap.builder().addProfiles(profile))
                .isInstanceOf(DistributedMapException.InvalidProfileSelector.class)
                .hasMessageContaining("readOnly");
    }

    @Test
    void rejectsTwoProfilesForOneType() {
        class First extends ProfileOf<Dto> {
            @Override
            protected void configure() {
            }
        }
        class Second extends ProfileOf<Dto> {
            @Override
            protected void configure() {
            }
        }

        assertThatThrownBy(() -> FxMap.builder().addProfiles(new First(), new Second()))
                .isInstanceOf(DistributedMapException.AmbiguousProfileConfiguration.class);
    }

    @Test
    void rejectsAnInvalidStringKey() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
                useDistributedKey("not a key");
            }
        };

        assertThatThrownBy(profile::rules).isInstanceOf(DistributedMapException.InvalidDistributedKeyName.class);
    }

    @Test
    void needsARequestClientOnceItHasProfiles() {
        var profile = new ProfileOf<Dto>() {
            @Override
            protected void configure() {
            }
        };

        assertThatThrownBy(() -> FxMap.builder().addProfiles(profile).build())
                .isInstanceOf(DistributedMapException.NoRequestClient.class);
    }
}
