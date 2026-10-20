package io.github.quyvu01.jfxmap.samples.user;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class User {
    @Id
    private UUID id;
    private String name;
    private String email;
    @ManyToOne(fetch = FetchType.LAZY)
    private Country country;
    @OneToMany(mappedBy = "user")
    private List<Address> addresses = new ArrayList<>();

    protected User() {
    }

    public User(UUID id, String name, String email, Country country) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.country = country;
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

    public Country getCountry() {
        return country;
    }

    public List<Address> getAddresses() {
        return addresses;
    }
}
