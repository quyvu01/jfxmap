package io.github.quyvu01.jfxmap.samples.order;

import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import org.springframework.stereotype.Component;

/** Which user fields an order shows, read from the user service by the "UserOf" key. */
@Component
public class OrderResponseProfile extends ProfileOf<OrderResponse> {
    @Override
    protected void configure() {
        useDistributedKey("UserOf")
                .of(OrderResponse::getUserId)
                .map(OrderResponse::getUserName)                           // the default property
                .map(OrderResponse::getUserEmail, "UserEmail")             // an exposed name
                .map(OrderResponse::getUserCountry, "Country?.Name")       // a navigation
                .map(OrderResponse::getUserNameUpper, "Name:upper")        // a function
                .map(OrderResponse::getUserAddressCount, "Addresses:count"); // a collection
    }
}
