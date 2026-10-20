package io.github.quyvu01.jfxmap.mongodb;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdFieldsTest {
    record Named(String id) {
    }

    record Annotated(@BsonId String code) {
    }

    record Renamed(@BsonProperty("user_code") String code) {
    }

    static class Bean {
        private String code;

        @BsonId
        public String getCode() {
            return code;
        }
    }

    static class Plain {
        String email;
    }

    static class NamedBean {
        private String id;

        public String getId() {
            return id;
        }
    }

    @Test
    void theIdPropertyOfAPojoIsStoredAsUnderscoreId() {
        assertThat(IdFields.storedName(NamedBean.class, "id")).isEqualTo("_id");
    }

    @Test
    void theIdComponentOfARecordKeepsItsName() {
        assertThat(IdFields.storedName(Named.class, "id")).isEqualTo("id");
    }

    @Test
    void aPropertyWithBsonIdIsStoredAsUnderscoreId() {
        assertThat(IdFields.storedName(Annotated.class, "code")).isEqualTo("_id");
        assertThat(IdFields.storedName(Bean.class, "code")).isEqualTo("_id");
    }

    @Test
    void aPropertyWithBsonPropertyIsStoredUnderThatName() {
        assertThat(IdFields.storedName(Renamed.class, "code")).isEqualTo("user_code");
    }

    @Test
    void anyOtherPropertyIsStoredUnderItsName() {
        assertThat(IdFields.storedName(Plain.class, "email")).isEqualTo("email");
    }
}
