package io.github.quyvu01.jfxmap.jpa;

import jakarta.persistence.EntityManagerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The translation of expressions into SQL, against a real PostgreSQL server. */
@Testcontainers
class JpaTranslationPostgresIntegrationTest extends TranslationContract {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Override
    EntityManagerFactory createEntityManagerFactory() {
        return Entities.entityManagerFactory("jpa-postgres", POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
