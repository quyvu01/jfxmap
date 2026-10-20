package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.jpa.Entities.Address;
import io.github.quyvu01.jfxmap.jpa.Entities.Country;
import io.github.quyvu01.jfxmap.jpa.Entities.Level;
import io.github.quyvu01.jfxmap.jpa.Entities.Product;
import io.github.quyvu01.jfxmap.jpa.Entities.PurchaseOrder;
import io.github.quyvu01.jfxmap.jpa.Entities.User;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ComparisonOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralNode;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.TernaryNode;
import io.github.quyvu01.jfxmap.provider.EntityExpressionEvaluator;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the translation of expressions into SQL must give on any database: the answers of the in-memory evaluator,
 * computed by the database. The H2 unit tests and the PostgreSQL integration tests run it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TranslationContract {
    static final UUID ALICE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    static final UUID BOB = UUID.fromString("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);

    /** Expressions the database answers, with the answers of the in-memory evaluator. */
    static final List<String> IN_DATABASE = List.of(
            // properties, exposed names, navigations
            "Name", "UserEmail", "Email", "Age", "Level", "Country?.Name", "Address.City", "Country?.Name ?? 'none'",
            // string functions
            "Name:upper", "Name:lower", "Name:trim", "Name:count", "Name:substring(1, 2)", "Name:substring(1)",
            "Name:replace('li', 'LI')", "Name:concat(' <', Email, '>')", "Name:concat(1.50, Email)",
            "Country?.Name:upper", "Name:split('i')",
            // math
            "Age:add(1)", "Age:subtract(Age)", "Age:multiply(2)", "Age:divide(Age)", "Age:divide(4)", "Age:mod(7)",
            "Score:round", "Score:round(1)", "Score:floor", "Score:ceil", "Score:abs", "Score:pow(2)",
            "Score:add(Age)", "Score:round(1):add(1)",
            // dates
            "BirthDate:year", "BirthDate:month", "BirthDate:day", "CreatedAt:year", "CreatedAt:hour",
            "CreatedAt:minute", "CreatedAt:second", "CreatedAt:dayOfWeek", "BirthDate:daysAgo",
            "CreatedAt:format('yyyy-MM-dd HH:mm')",
            // conditions and conditionals
            "Email endswith '.com'", "Name contains 'li'", "Name startswith 'A'", "Score > 5", "Score = null",
            "Level = 'GOLD' ? 1 : 0", "Level != 'GOLD'", "Age >= 30 && Name != 'Bob' ? 'senior' : 'junior'",
            "Country?.Name = null || Age < 26",
            // aggregations of collections
            "Orders:count", "Orders:sum(Total)", "Orders:avg(Total)", "Orders:min(Total)", "Orders:max(Total)",
            "Orders:max(Status)", "Orders:sum(Quantity)", "Orders:avg(Quantity)", "Orders:min(Quantity)",
            "Orders:where(Status = 'Done'):sum(Total)", "Orders:where(Status != 'Done'):count",
            "Orders:where(Total > 5 && Status startswith 'D'):count", "Orders:where(Quantity > 1):count",
            "Orders:where(Status = 'Done' || Quantity >= 5):orderBy(Total):count",
            "Orders:any", "Orders:any(Status = 'Open')", "Orders:all(Total > 1)", "Orders:all(Status = 'Done')",
            "Orders:all", "Orders:count > 0 ? 'buyer' : 'visitor'", "Orders:sum(Total):round(1)",
            "Orders:where(Status = 'Done'):count = 2", "Tags:count",
            // projections
            "{Id, Name, Orders:count as OrderCount}", "Country.{Id, Name}",
            "Address.{City}", "{Name, (Age > 26 ? 'yes' : 'no') as Older}",
            // items of collections
            "Orders.{Id, Total}", "Orders:where(Status = 'Done'):orderBy(Total desc).{Id, Total}",
            "Orders:orderBy(Total desc):first.{Id, Total}", "Orders:orderBy(Total):last.{Id}",
            "Orders:orderBy(Total desc):limit(2).{Id}", "Orders:orderBy(Total):offset(1).{Id}",
            "Orders:orderBy(Status desc, Total).{Id, Status}", "Orders.{Id, Quantity:multiply(2) as Double}",
            "Orders:distinct(Status)", "Orders:distinct('Status')", "Tags", "Tags:first",
            "Orders:orderBy(Quantity desc):first.{Id, Quantity}",
            // literals converted to the type of the property, and conditions on null
            "Score != null", "Score >= 7.5 && Score <= 8",
            "Name contains null", "Level = null",
            // functions computed in memory from the values the query read
            "Score:round(Age)", "Name:replace(Email, 'x')", "Age:pow(Age)", "Name:count:add(Age)",
            "BirthDate:hour", "Address.City:upper", "Orders:where(Status = 'Done'):avg(Quantity)");

    /** Expressions read from the database that fail in memory afterwards, with the evaluator's error. */
    static final List<String> FAILING_AFTER_THE_QUERY = List.of("Name:substring(Age)", "Age:upper", "Tags:sum",
            "Name:format('yyyy')", "CreatedAt:format(1)");

    /** Expressions that need the entities: they are evaluated in memory. */
    static final List<String> IN_MEMORY = List.of("Orders:groupBy(Status).{Status, :count as Count}", "Nickname",
            "Orders:orderBy(Total):limit(2):sum(Total)", "Orders:first.Total", "Nickname:upper",
            "Level = 'PLATINUM'", "Age = 30.5", "Age > 'x'", "Orders:distinct(Missing)", "Missing",
            "Name:", "{Name, Name}", "Orders:orderBy(Missing).{Id}", "Orders.{Id, User.Tags as Tags}");

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::getId);
            defaultProperty(User::getName);
            useDistributedKey("UserOf");
            exposedName(User::getEmail, "UserEmail");
        }
    }

    EntityManagerFactory emf;
    JpaQueryHandler<User> handler;
    EntityExpressionEvaluator<User> evaluator;

    abstract EntityManagerFactory createEntityManagerFactory();

    @BeforeAll
    void seed() {
        emf = createEntityManagerFactory();
        emf.runInTransaction(em -> {
            var vietnam = new Country(1, "Vietnam");
            em.persist(vietnam);
            var pen = new Product(7, "P-7", "Pen");
            em.persist(pen);
            var alice = new User(ALICE, "Alice", "alice@example.com", vietnam).details(30, LocalDate.of(1995, 3, 15),
                    LocalDateTime.of(2024, 12, 25, 14, 30, 45), 7.5, Level.GOLD, new Address("Hanoi"),
                    List.of("a", "b"));
            var bob = new User(BOB, "Bob", "bob@example.org", null).details(25, LocalDate.of(2000, 1, 1),
                    LocalDateTime.of(2025, 6, 1, 8, 0, 0), null, null, new Address("Paris"), List.of());
            em.persist(alice);
            em.persist(bob);
            em.persist(new PurchaseOrder(1, "Done", new BigDecimal("10.50"), alice, 2, pen));
            em.persist(new PurchaseOrder(2, "Done", new BigDecimal("4.50"), alice, 1, null));
            em.persist(new PurchaseOrder(3, "Open", new BigDecimal("100.00"), alice, 5, pen));
        });
        handler = new JpaQueryHandler<>(new UserConfig(), emf, Runnable::run, CLOCK);
        evaluator = new EntityExpressionEvaluator<>(new UserConfig(), CLOCK);
    }

    @AfterAll
    void close() {
        emf.close();
    }

    ItemsResponse handle(String... expressions) {
        return handler.handle(new MapRequest(List.of(ALICE.toString(), BOB.toString()), Arrays.asList(expressions)),
                MapContext.empty()).toCompletableFuture().join();
    }

    // The answer of the in-memory evaluator, on entities loaded with lazy associations
    ItemsResponse inMemory(String... expressions) {
        try (var em = emf.createEntityManager()) {
            return evaluator.answer(new MapRequest(List.of(ALICE.toString(), BOB.toString()),
                    Arrays.asList(expressions)), id -> em.find(User.class, UUID.fromString(id)));
        }
    }

    @ParameterizedTest
    @FieldSource("IN_DATABASE")
    void answersInTheDatabaseAsTheEvaluatorDoes(String expression) {
        assertThat(handler.runsInDatabase(expression)).as("runs in the database").isTrue();

        var response = handle(expression);

        assertThat(response).isEqualTo(inMemory(expression));
        assertThat(response.errors()).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("FAILING_AFTER_THE_QUERY")
    void reportsTheErrorOfTheEvaluatorForWhatFailsAfterTheQuery(String expression) {
        assertThat(handler.runsInDatabase(expression)).as("runs in the database").isTrue();

        var response = handle(expression);

        assertThat(response).isEqualTo(inMemory(expression));
        assertThat(response.errors()).extracting(ExpressionError::expression).containsExactly(expression);
    }

    @ParameterizedTest
    @FieldSource("IN_MEMORY")
    void evaluatesInMemoryWhatNeedsTheEntities(String expression) {
        assertThat(handler.runsInDatabase(expression)).isFalse();

        assertThat(handle(expression)).isEqualTo(inMemory(expression));
    }

    @Test
    void answersAllTheExpressionsOfARequestInOneQuery() {
        var scalars = List.of("Name", "UserEmail", "Country?.Name:upper", "Orders:where(Status = 'Done'):sum(Total)",
                "Orders:any(Status = 'Open')", "{Id, Orders:count as Count}", "Orders:avg(Total)");
        SqlLog.clear();

        var response = handle(scalars.toArray(String[]::new));

        assertThat(SqlLog.statements()).hasSize(1);
        assertThat(response).isEqualTo(inMemory(scalars.toArray(String[]::new)));
    }

    @Test
    void readsTheItemsOfACollectionWithOneMoreQuery() {
        SqlLog.clear();

        var response = handle("Name", "Orders:where(Status = 'Done'):orderBy(Total).{Id, Total}");

        assertThat(response.items().getFirst().values()).extracting(ValueResponse::value)
                .containsExactly("\"Alice\"", "[{\"Id\":2,\"Total\":4.50},{\"Id\":1,\"Total\":10.50}]");
        assertThat(response.items().get(1).values()).extracting(ValueResponse::value)
                .containsExactly("\"Bob\"", "[]");
        assertThat(SqlLog.statements()).hasSize(2);
    }

    @Test
    void mixesTranslatedAndInMemoryExpressionsInTheRequestedOrder() {
        var expressions = new ArrayList<String>();
        expressions.add("Orders:groupBy(Status).{Status, :count as Count}");
        expressions.add(null);
        expressions.add("Orders:sum(Total)");
        expressions.add("Nickname");

        var response = handler.handle(new MapRequest(List.of(BOB.toString(), ALICE.toString()), expressions),
                MapContext.empty()).toCompletableFuture().join();

        try (var em = emf.createEntityManager()) {
            assertThat(response).isEqualTo(evaluator.answer(new MapRequest(List.of(BOB.toString(), ALICE.toString()),
                    expressions), id -> em.find(User.class, UUID.fromString(id))));
        }
        assertThat(response.items()).extracting(DataResponse::id).containsExactly(BOB.toString(), ALICE.toString());
    }

    @Test
    void tellsWhatRunsInTheDatabase() {
        assertThat(handler.runsInDatabase(null)).as("the default property").isTrue();
        assertThat(handler.runsInDatabase("Name:")).as("an expression that does not parse").isFalse();
    }

    @Test
    void evaluatesInMemoryAnExpressionThePersistenceProviderRejects() {
        // Name > Age compares a string with a number: Hibernate rejects the query, the expression is evaluated in
        // memory, and the provider is not asked again
        var rejected = new TernaryNode(new BinaryConditionNode(new PropertyNode("Name"),
                ComparisonOperator.GREATER_THAN, new PropertyNode("Age")), LiteralNode.string("yes"),
                LiteralNode.string("no"));
        try (var em = emf.createEntityManager()) {
            var compiled = handler.compile(em, List.of(new PropertyNode("Name"), rejected));
            assertThat(compiled.plan().readers()[0]).isNotNull();
            assertThat(compiled.plan().readers()[1]).isNull();

            assertThat(handler.compile(em, List.of(rejected)).plan().readers()[0]).isNull();
        }
    }

    @Test
    void readsANullAssociationAsNullAsTheEfCoreHandlerDoes() {
        // The evaluator fails on Bob, who has no country; the database gives null
        var response = handle("Country.Name", "Country.Name:upper", "{Id, Country.Name as CountryName}",
                "Orders:where(Product.Code = 'P-7'):count", "Orders:orderBy(Id).{Id, Product.Title as Title}");

        assertThat(response.errors()).isEmpty();
        assertThat(response.items().get(0).values()).extracting(ValueResponse::value).containsExactly(
                "\"Vietnam\"", "\"VIETNAM\"", "{\"Id\":\"" + ALICE + "\",\"CountryName\":\"Vietnam\"}", "2",
                "[{\"Id\":1,\"Title\":\"Pen\"},{\"Id\":2,\"Title\":null},{\"Id\":3,\"Title\":\"Pen\"}]");
        assertThat(response.items().get(1).values()).extracting(ValueResponse::value).containsExactly(null, null,
                "{\"Id\":\"" + BOB + "\",\"CountryName\":null}", "0", "[]");
    }

    @Test
    void convertsALiteralToTheTypeOfThePropertyAsFxMapDoes() {
        // FxMap converts 'text' to a Guid; the evaluator compares a UUID with a string, which is never equal
        var response = handle("Id = '" + ALICE + "'");

        assertThat(response.items()).extracting(item -> item.values().getFirst().value())
                .containsExactly("true", "false");
    }

    @Test
    void reportsAnExpressionThatFailsInMemoryAfterTheQuery() {
        var response = handle("Name:format('yyyy')", "Name");

        assertThat(response.errors()).extracting(ExpressionError::expression).containsExactly("Name:format('yyyy')");
        assertThat(response.errors().getFirst().message()).contains("non-date");
        assertThat(response.items()).allSatisfy(item -> assertThat(item.values())
                .extracting(ValueResponse::expression).containsExactly("Name"));
    }
}
