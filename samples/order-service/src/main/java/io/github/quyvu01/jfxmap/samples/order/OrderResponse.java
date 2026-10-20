package io.github.quyvu01.jfxmap.samples.order;

import java.math.BigDecimal;
import java.util.UUID;

/** An order with its user's fields, which jfxmap fills from the user service. */
public class OrderResponse {
    private Long id;
    private String product;
    private BigDecimal total;
    private UUID userId;
    private String userName;
    private String userEmail;
    private String userCountry;
    private String userNameUpper;
    private Integer userAddressCount;

    public OrderResponse() {
    }

    public OrderResponse(Order order) {
        this.id = order.getId();
        this.product = order.getProduct();
        this.total = order.getTotal();
        this.userId = order.getUserId();
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

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserCountry() {
        return userCountry;
    }

    public void setUserCountry(String userCountry) {
        this.userCountry = userCountry;
    }

    public String getUserNameUpper() {
        return userNameUpper;
    }

    public void setUserNameUpper(String userNameUpper) {
        this.userNameUpper = userNameUpper;
    }

    public Integer getUserAddressCount() {
        return userAddressCount;
    }

    public void setUserAddressCount(Integer userAddressCount) {
        this.userAddressCount = userAddressCount;
    }
}
