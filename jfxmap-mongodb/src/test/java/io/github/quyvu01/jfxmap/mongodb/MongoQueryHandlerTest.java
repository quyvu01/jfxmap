package io.github.quyvu01.jfxmap.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the handler decides before it reads anything: no server is needed (the client connects lazily). */
class MongoQueryHandlerTest {
    record User(String id, String name) {
    }

    record Event(LocalDate id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
        }
    }

    static class EventConfig extends EntityConfigureOf<Event> {
        @Override
        protected void configure() {
            id(Event::id);
            useDistributedKey("EventOf");
        }
    }

    private static MongoClient client;
    private static MongoCollection<Document> collection;

    @BeforeAll
    static void createClient() {
        client = MongoClients.create(MongoClientSettings.builder()
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(100, TimeUnit.MILLISECONDS))
                .applyConnectionString(new ConnectionString("mongodb://localhost:1"))
                .build());
        collection = client.getDatabase("test").getCollection("users");
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    void answersForTheKeyAndTheEntityOfItsConfiguration() {
        var handler = new MongoQueryHandler<>(new UserConfig(), collection);

        assertThat(handler.key()).isEqualTo(DistributedKeyRef.of("UserOf"));
        assertThat(handler.entityType()).isEqualTo(User.class);
        assertThat(handler.idField()).isEqualTo("id");
        assertThat(handler.collection().getDocumentClass()).isEqualTo(User.class);
    }

    @Test
    void aCollectionWithoutCodecForTheEntityGetsAPojoCodec() {
        var handler = new MongoQueryHandler<>(new UserConfig(), collection);

        assertThat(handler.collection().getCodecRegistry().get(User.class)).isNotNull();
    }

    @Test
    void theIdFieldCanBeGiven() {
        var handler = MongoQueryHandler.builder(new UserConfig(), collection).idField("user_id").build();

        assertThat(handler.idField()).isEqualTo("user_id");
    }

    @Test
    void theBuilderRejectsBlankOrMissingOptions() {
        var builder = MongoQueryHandler.builder(new UserConfig(), collection);

        assertThatThrownBy(() -> builder.idField(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.executor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.clock(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MongoQueryHandler.builder(null, collection)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MongoQueryHandler.builder(new UserConfig(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anIdTypeThatCannotBeConvertedIsRejected() {
        assertThatThrownBy(() -> new MongoQueryHandler<>(new EventConfig(), collection))
                .isInstanceOf(MongoMapException.UnsupportedIdType.class)
                .hasMessageContaining("java.time.LocalDate");
    }

    @Test
    void aNullRequestIsRejected() {
        var handler = new MongoQueryHandler<>(new UserConfig(), collection);

        assertThatThrownBy(() -> handler.handle(null, null)).isInstanceOf(NullPointerException.class);
    }
}
