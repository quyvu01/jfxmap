package io.github.quyvu01.jfxmap.samples.order;

import io.github.quyvu01.jfxmap.DistributedMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OrderController {
    private final OrderRepository orders;
    private final DistributedMapper mapper;

    public OrderController(OrderRepository orders, DistributedMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    /** Every order, with the user fields of all of them fetched in one gRPC call. */
    @GetMapping("/orders")
    public List<OrderResponse> orders() {
        var responses = orders.findAll().stream().map(OrderResponse::new).toList();
        mapper.map(responses);
        return responses;
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderResponse> order(@PathVariable long id) {
        return orders.findById(id).map(order -> {
            var response = new OrderResponse(order);
            mapper.map(response);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }
}
