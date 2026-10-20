package io.github.quyvu01.jfxmap.samples.sales;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class Customer {
    @Id
    private UUID id;
    private String name;
    private String email;
    private String city;
    @OneToMany(mappedBy = "customer")
    private List<SalesOrder> orders = new ArrayList<>();

    protected Customer() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getCity() {
        return city;
    }

    public List<SalesOrder> getOrders() {
        return orders;
    }
}
