package io.github.quyvu01.jfxmap.samples.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class Address {
    @Id
    @GeneratedValue
    private Long id;
    private String city;
    @ManyToOne
    private User user;

    protected Address() {
    }

    public Address(String city, User user) {
        this.city = city;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public String getCity() {
        return city;
    }

    public User getUser() {
        return user;
    }
}
