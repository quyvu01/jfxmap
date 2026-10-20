package io.github.quyvu01.jfxmap.samples.user;

import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Seeds the users that the order service's sample orders refer to. */
@Component
public class SampleUsers implements ApplicationRunner {
    public static final UUID ALICE = UUID.fromString("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    public static final UUID BOB = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    public static final UUID CAROL = UUID.fromString("c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c");

    private final EntityManager entityManager;

    public SampleUsers(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (entityManager.find(User.class, ALICE) != null) return;
        var vietnam = new Country("VN", "Vietnam");
        var france = new Country("FR", "France");
        entityManager.persist(vietnam);
        entityManager.persist(france);
        var alice = new User(ALICE, "Alice Nguyen", "alice@example.com", vietnam);
        var bob = new User(BOB, "Bob Martin", "bob@example.com", france);
        var carol = new User(CAROL, "Carol Tran", "carol@example.com", null);
        entityManager.persist(alice);
        entityManager.persist(bob);
        entityManager.persist(carol);
        entityManager.persist(new Address("Hanoi", alice));
        entityManager.persist(new Address("Da Nang", alice));
        entityManager.persist(new Address("Lyon", bob));
    }
}
