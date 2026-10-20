package io.github.quyvu01.jfxmap.samples.sales;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * The GraphQL queries ({@code /graphql}). The product fields of {@link OrderResponse} are not filled here: the
 * starter's jfxmap-graphql wiring resolves the ones a query selects from catalog-service.
 */
@Controller
public class SalesGraphQlController {
    private final SalesOrderRepository orders;
    private final SalesController rest;

    public SalesGraphQlController(SalesOrderRepository orders, SalesController rest) {
        this.orders = orders;
        this.rest = rest;
    }

    @QueryMapping
    public List<OrderResponse> orders() {
        return orders.findResponses();
    }

    @QueryMapping
    public OrderResponse order(@Argument long id) {
        return orders.findResponse(id).orElse(null);
    }

    @QueryMapping
    public List<SalesController.CustomerResponse> customers() {
        return rest.customers();
    }
}
