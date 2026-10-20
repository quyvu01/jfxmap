package io.github.quyvu01.jfxmap.jpa;

import jakarta.persistence.EntityManagerFactory;

/** The translation of expressions into SQL, on H2. */
class JpaTranslationTest extends TranslationContract {
    @Override
    EntityManagerFactory createEntityManagerFactory() {
        return Entities.entityManagerFactory("jpa-translation");
    }
}
