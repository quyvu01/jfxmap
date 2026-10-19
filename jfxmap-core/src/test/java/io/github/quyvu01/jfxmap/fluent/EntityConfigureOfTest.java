package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityConfigureOfTest {
    static final class UserOf implements DistributedKey {
    }

    record User(long id, String name, String email) {
    }

    @Test
    void exposesItsConfiguration() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                id(User::id);
                defaultProperty(User::name);
                useDistributedKey(UserOf.class);
                exposedName(User::email, "UserEmail");
            }
        };

        assertThat(config.entityType()).isEqualTo(User.class);
        assertThat(config.idProperty()).isEqualTo("id");
        assertThat(config.defaultProperty()).isEqualTo("name");
        assertThat(config.key()).isEqualTo(DistributedKeyRef.of(UserOf.class));
        assertThat(config.exposedNames()).isEqualTo(Map.of("UserEmail", "email"));
    }

    @Test
    void needsAnId() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class);
            }
        };

        assertThatThrownBy(config::key).isInstanceOf(DistributedMapException.EntityIdNotConfigured.class);
    }

    @Test
    void needsADistributedKey() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                id(User::id);
            }
        };

        assertThatThrownBy(config::idProperty)
                .isInstanceOf(DistributedMapException.InvalidDistributedKeyConfiguration.class);
    }

    @Test
    void rejectsAnIdDeclaredTwice() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                id(User::id);
                id(User::name);
                useDistributedKey("UserOf");
            }
        };

        assertThatThrownBy(config::key).isInstanceOf(DistributedMapException.InvalidEntitySelector.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void rejectsAnExposedNameUsedTwice() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                id(User::id);
                useDistributedKey("UserOf");
                exposedName(User::email, "Alias");
                exposedName(User::name, "Alias");
            }
        };

        assertThatThrownBy(config::key).isInstanceOf(DistributedMapException.DuplicatedNameByExposedName.class);
    }

    @Test
    void cannotBeChangedOutsideConfigure() {
        var config = new EntityConfigureOf<User>() {
            @Override
            protected void configure() {
                id(User::id);
                useDistributedKey("UserOf");
            }

            void later() {
                defaultProperty(User::name);
            }
        };

        assertThatThrownBy(config::later).isInstanceOf(IllegalStateException.class);
    }
}
