package io.github.quyvu01.jfxmap.samples.user;

import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import org.springframework.stereotype.Component;

/**
 * What the user service exposes. The string key "UserOf" is the one a .NET service would declare with
 * {@code UseDistributedKey("UserOf")}, so both can call this service.
 */
@Component
public class UserConfig extends EntityConfigureOf<User> {
    @Override
    protected void configure() {
        id(User::getId);
        defaultProperty(User::getName);
        useDistributedKey("UserOf");
        exposedName(User::getEmail, "UserEmail");
    }
}
