package io.github.quyvu01.jfxmap.samples.sales;

import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import org.springframework.stereotype.Component;

/** Which product fields an order shows, read from catalog-service (.NET) by the string key "ProductOf". */
@Component
public class OrderResponseProfile extends ProfileOf<OrderResponse> {
    @Override
    protected void configure() {
        useDistributedKey("ProductOf")
                .of(OrderResponse::getProductId)
                .map(OrderResponse::getProductName)                    // the default property
                .map(OrderResponse::getUnitPrice, "UnitPrice")         // an exposed name
                .map(OrderResponse::getCategoryName, "Category.Name"); // a navigation, joined by EF Core
    }
}
