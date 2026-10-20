package io.github.quyvu01.jfxmap.samples.order;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    private Long id;
    private String product;
    private BigDecimal total;
    /** The user, owned by the user service. */
    private UUID userId;

    protected Order() {
    }

    public Order(Long id, String product, BigDecimal total, UUID userId) {
        this.id = id;
        this.product = product;
        this.total = total;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public String getProduct() {
        return product;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public UUID getUserId() {
        return userId;
    }
}
