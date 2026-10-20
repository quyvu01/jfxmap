package io.github.quyvu01.jfxmap.samples.sales;

import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import org.springframework.stereotype.Component;

/**
 * What this service exposes to others: customers under the string key "CustomerOf", answered from JPA. A string key
 * is what lets catalog-service (.NET FxMap) ask for it with {@code UseDistributedKey("CustomerOf")}.
 */
@Component
public class CustomerConfig extends EntityConfigureOf<Customer> {
    @Override
    protected void configure() {
        id(Customer::getId);
        defaultProperty(Customer::getName);
        useDistributedKey("CustomerOf");
        exposedName(Customer::getEmail, "CustomerEmail");
    }
}
