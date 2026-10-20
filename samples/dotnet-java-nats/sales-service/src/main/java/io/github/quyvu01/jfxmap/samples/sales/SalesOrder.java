package io.github.quyvu01.jfxmap.samples.sales;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sales_order")
public class SalesOrder {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Customer customer;
    /** The product, owned by catalog-service (.NET). */
    private int productId;
    private int quantity;

    protected SalesOrder() {
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public int getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
