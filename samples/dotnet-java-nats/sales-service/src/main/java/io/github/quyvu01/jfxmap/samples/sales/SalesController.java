package io.github.quyvu01.jfxmap.samples.sales;

import io.github.quyvu01.jfxmap.DistributedMapper;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class SalesController {
    private final SalesOrderRepository orders;
    private final CustomerRepository customers;
    private final DistributedMapper mapper;

    public SalesController(SalesOrderRepository orders, CustomerRepository customers, DistributedMapper mapper) {
        this.orders = orders;
        this.customers = customers;
        this.mapper = mapper;
    }

    /** Every order, with the product fields of all of them fetched from catalog-service in one NATS request. */
    @GetMapping("/orders")
    public List<OrderResponse> orders() {
        var responses = orders.findResponses();
        mapper.map(responses);
        return responses;
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderResponse> order(@PathVariable long id) {
        return orders.findResponse(id).map(response -> {
            mapper.map(response);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    public record CustomerResponse(UUID id, String name, String email, String city) {
    }

    @GetMapping("/customers")
    public List<CustomerResponse> customers() {
        return customers.findAll(Sort.by("name")).stream()
                .map(c -> new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getCity()))
                .toList();
    }
}
