package io.github.quyvu01.jfxmap.samples.sales;

import java.math.BigDecimal;
import java.util.UUID;

/** An order with its product's fields, which jfxmap fills from catalog-service (.NET) over NATS. */
public class OrderResponse {
    private Long id;
    private UUID customerId;
    private String customerName;
    private Integer productId;
    private int quantity;
    private String productName;
    private BigDecimal unitPrice;
    private String categoryName;

    public OrderResponse() {
    }

    public OrderResponse(Long id, UUID customerId, String customerName, Integer productId, int quantity) {
        this.id = id;
        this.customerId = customerId;
        this.customerName = customerName;
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Integer getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    /** The unit price from catalog-service times the quantity; empty until the order is mapped. */
    public BigDecimal getTotal() {
        return unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
