package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.Models.Country;
import io.github.quyvu01.jfxmap.Models.MemberDto;
import io.github.quyvu01.jfxmap.Models.MemberProfile;
import io.github.quyvu01.jfxmap.Models.PageDto;
import io.github.quyvu01.jfxmap.Models.PageProfile;
import io.github.quyvu01.jfxmap.Models.UserDetailDto;
import io.github.quyvu01.jfxmap.Models.UserDetailProfile;
import io.github.quyvu01.jfxmap.Models.UserOf;
import io.github.quyvu01.jfxmap.Models.UserSummaryDto;
import io.github.quyvu01.jfxmap.Models.UserSummaryProfile;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedMapperTest {
    private static final DistributedKeyRef USER = DistributedKeyRef.of(UserOf.class);
    private static final DistributedKeyRef PROVINCE = DistributedKeyRef.of("ProvinceOf");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final FakeRequestClient client = new FakeRequestClient()
            .value(USER, ALICE, null, "\"Alice\"")
            .value(USER, ALICE, "UserEmail", "\"alice@example.com\"")
            .value(USER, ALICE, "Country.Name", "\"Vietnam\"")
            .value(USER, BOB, null, "\"Bob\"")
            .value(USER, BOB, "UserEmail", "\"bob@example.com\"")
            .value(USER, BOB, "Country.Name", "\"Japan\"");

    private DistributedMapper mapper(ProfileOf<?>... profiles) {
        return FxMap.builder().addProfiles(profiles).requestClient(client).throwIfException().build();
    }

    @Test
    void fillsEveryRuleOfAnObject() {
        var dto = new UserSummaryDto(ALICE);

        mapper(new UserSummaryProfile()).map(dto);

        assertThat(dto.getUserName()).isEqualTo("Alice");
        assertThat(dto.getUserEmail()).isEqualTo("alice@example.com");
        assertThat(dto.getCountryName()).isEqualTo("Vietnam");
    }

    @Test
    void batchesDistinctIdsAndExpressionsIntoOneRequestPerKey() {
        var dtos = List.of(new UserSummaryDto(ALICE), new UserSummaryDto(BOB), new UserSummaryDto(ALICE));

        mapper(new UserSummaryProfile()).map(dtos);

        assertThat(client.sent).hasSize(1);
        var request = client.sent.getFirst().request();
        assertThat(request.selectorIds()).containsExactlyInAnyOrder(ALICE.toString(), BOB.toString());
        assertThat(request.expressions()).containsExactlyInAnyOrder(null, "UserEmail", "Country.Name");
        assertThat(dtos).extracting(UserSummaryDto::getUserName).containsExactly("Alice", "Bob", "Alice");
        assertThat(dtos).extracting(UserSummaryDto::getCountryName).containsExactly("Vietnam", "Japan", "Vietnam");
    }

    @Test
    void sendsNothingWhenNoObjectHasAnId() {
        var dto = new UserSummaryDto(null);

        mapper(new UserSummaryProfile()).map(List.of(dto));

        assertThat(client.sent).isEmpty();
        assertThat(dto.getUserName()).isNull();
    }

    @Test
    void leavesThePropertiesOfAnUnknownIdAlone() {
        var dto = new UserSummaryDto(UUID.randomUUID());
        dto.setUserName("unchanged");

        mapper(new UserSummaryProfile()).map(dto);

        assertThat(dto.getUserName()).isEqualTo("unchanged");
    }

    @Test
    void runsTheRulesWhoseKeyIsFilledByAnotherRuleAfterIt() {
        client.value(USER, ALICE, "ProvinceId", "7")
                .value(USER, BOB, "ProvinceId", "8")
                .value(PROVINCE, 7, null, "\"Hanoi\"")
                .value(PROVINCE, 8, null, "\"Osaka\"");
        var members = List.of(new MemberDto(ALICE), new MemberDto(BOB));

        mapper(new MemberProfile()).map(members);

        assertThat(client.sent).extracting(FakeRequestClient.Sent::key).containsExactly(USER, PROVINCE);
        assertThat(client.sent.get(1).request().selectorIds()).containsExactlyInAnyOrder("7", "8");
        assertThat(members).extracting(MemberDto::getProvinceId).containsExactly(7, 8);
        assertThat(members).extracting(MemberDto::getProvinceName).containsExactly("Hanoi", "Osaka");
    }

    @Test
    void readsTheJsonValuesAsTheTypesOfTheTargetProperties() {
        client.value(USER, ALICE, "Birthday", "\"1990-04-30\"")
                .value(USER, ALICE, "Country", "{\"Name\":\"Vietnam\",\"Code\":\"VN\"}")
                .value(USER, ALICE, "Tags", "[\"admin\",\"owner\"]");
        var dto = new UserDetailDto(ALICE);

        mapper(new UserDetailProfile()).map(dto);

        assertThat(dto.birthday()).isEqualTo(LocalDate.of(1990, 4, 30));
        assertThat(dto.country()).isEqualTo(new Country("Vietnam", "VN"));
        assertThat(dto.tags()).containsExactly("admin", "owner");
    }

    @Test
    void mapsTheObjectsItWritesOnTheNextLevel() {
        client.value(USER, ALICE, "Summary", "{\"userId\":\"" + BOB + "\"}");
        var dto = new UserDetailDto(ALICE);

        mapper(new UserDetailProfile(), new UserSummaryProfile()).map(dto);

        assertThat(dto.summary().getUserId()).isEqualTo(BOB);
        assertThat(dto.summary().getUserName()).isEqualTo("Bob");
        assertThat(client.sent).hasSize(2);
    }

    @Test
    void walksCollectionsMapsArraysAndNestedObjects() {
        var page = new PageDto();
        page.items.add(new UserSummaryDto(ALICE));
        page.byName = Map.of("bob", new UserSummaryDto(BOB));
        page.pinned = new UserSummaryDto[]{new UserSummaryDto(BOB)};
        page.featured = new UserSummaryDto(ALICE);

        mapper(new PageProfile(), new UserSummaryProfile()).map(page);

        assertThat(page.items.getFirst().getUserName()).isEqualTo("Alice");
        assertThat(page.byName.get("bob").getUserName()).isEqualTo("Bob");
        assertThat(page.pinned[0].getUserName()).isEqualTo("Bob");
        assertThat(page.featured.getUserName()).isEqualTo("Alice");
        assertThat(client.sent).hasSize(1);
    }

    @Test
    void mapsAnObjectReachedTwiceOnce() {
        var shared = new UserSummaryDto(ALICE);

        mapper(new UserSummaryProfile()).map(Arrays.asList(shared, shared));

        assertThat(client.sent).hasSize(1);
        assertThat(shared.getUserName()).isEqualTo("Alice");
    }

    @Test
    void usesTheProfileOfTheNearestSuperclass() {
        class SpecialSummary extends UserSummaryDto {
            SpecialSummary(UUID userId) {
                super(userId);
            }
        }
        var dto = new SpecialSummary(BOB);

        mapper(new UserSummaryProfile()).map(dto);

        assertThat(dto.getUserName()).isEqualTo("Bob");
    }

    @Test
    void choosesConditionalExpressionsPerCallAndSendsTheHeaders() {
        class Profile extends ProfileOf<UserSummaryDto> {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class)
                        .of(UserSummaryDto::getUserId)
                        .map(UserSummaryDto::getUserName, c -> c
                                .when(ctx -> ctx.headers().containsKey("x-email"))
                                .expression("UserEmail")
                                .otherwise((String) null));
            }
        }
        var mapper = mapper(new Profile());
        var plain = new UserSummaryDto(ALICE);
        var withEmail = new UserSummaryDto(ALICE);

        mapper.map(plain);
        mapper.map(withEmail, Map.of("x-email", "1"));

        assertThat(plain.getUserName()).isEqualTo("Alice");
        assertThat(withEmail.getUserName()).isEqualTo("alice@example.com");
        assertThat(client.sent.get(1).context().headers()).containsEntry("x-email", "1");
    }

    @Test
    void conditionalExpressionsCanUseTheRegisteredServices() {
        record Flags(boolean emailAsName) {
        }
        class Profile extends ProfileOf<UserSummaryDto> {
            @Override
            protected void configure() {
                useDistributedKey(UserOf.class)
                        .of(UserSummaryDto::getUserId)
                        .map(UserSummaryDto::getUserName, c -> c
                                .whenAsync(ctx -> CompletableFuture.completedFuture(
                                        ctx.service(Flags.class).emailAsName()))
                                .expression("UserEmail")
                                .otherwise("Country.Name"));
            }
        }
        var mapper = FxMap.builder().addProfiles(new Profile()).requestClient(client)
                .services(type -> type == Flags.class ? new Flags(false) : null).build();
        var dto = new UserSummaryDto(BOB);

        mapper.map(dto);

        assertThat(dto.getUserName()).isEqualTo("Japan");
    }

    @Test
    void throwsTheExpressionsTheOwnerCouldNotEvaluate() {
        RequestClient failing = (key, request, context) -> CompletableFuture.completedFuture(
                new ItemsResponse(List.of(), List.of(new ExpressionError("Country.Name", "boom"))));
        var mapper = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(failing)
                .throwIfException().build();

        assertThatThrownBy(() -> mapper.map(new UserSummaryDto(ALICE)))
                .isInstanceOf(DistributedMapException.ExpressionsFailed.class)
                .hasMessageContaining("Country.Name");
    }

    @Test
    void withoutThrowIfExceptionSkipsOnlyThePropertiesOfAFailedExpression() {
        RequestClient partly = (key, request, context) -> client.request(key, request, context)
                .thenApply(r -> new ItemsResponse(r.items(), List.of(new ExpressionError("Country.Name", "boom"))));
        var mapper = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(partly).build();
        var dto = new UserSummaryDto(ALICE);

        mapper.map(dto);

        assertThat(dto.getUserName()).isEqualTo("Alice");
        assertThat(dto.getCountryName()).isNull();
    }

    @Test
    void failedRequestsAreThrownOrSkipped() {
        RequestClient down = (key, request, context) -> CompletableFuture.failedFuture(new IllegalStateException("down"));
        var throwing = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(down)
                .throwIfException().build();
        var lenient = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(down).build();
        var dto = new UserSummaryDto(ALICE);

        assertThatThrownBy(() -> throwing.map(dto)).isInstanceOf(IllegalStateException.class).hasMessage("down");
        lenient.map(dto);
        assertThat(dto.getUserName()).isNull();
    }

    @Test
    void aValueThatDoesNotFitThePropertyIsThrownOrSkipped() {
        client.value(USER, ALICE, "Birthday", "\"not a date\"");
        var dto = new UserDetailDto(ALICE);

        assertThatThrownBy(() -> mapper(new UserDetailProfile()).map(dto))
                .isInstanceOf(DistributedMapException.ValueWriteFailed.class)
                .hasMessageContaining("birthday");
        FxMap.builder().addProfiles(new UserDetailProfile()).requestClient(client).build().map(dto);
        assertThat(dto.birthday()).isNull();
    }

    static class Node {
        UUID id;
        Node next;

        Node() {
        }

        Node(UUID id) {
            this.id = id;
        }

        UUID id() {
            return id;
        }

        Node next() {
            return next;
        }
    }

    static class NodeProfile extends ProfileOf<Node> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class).of(Node::id).map(Node::next, "Next");
        }
    }

    @Test
    void stopsAtTheMaximumNestingDepth() {
        // Each node maps to another node that needs mapping: an endless chain
        client.value(USER, ALICE, "Next", "{\"id\":\"" + ALICE + "\"}");

        var throwing = FxMap.builder().addProfiles(new NodeProfile()).requestClient(client)
                .throwIfException().maxNestingDepth(3).build();
        assertThatThrownBy(() -> throwing.map(new Node(ALICE)))
                .isInstanceOf(DistributedMapException.MaxNestingDepthReached.class);

        client.sent.clear();
        var node = new Node(ALICE);
        FxMap.builder().addProfiles(new NodeProfile()).requestClient(client).maxNestingDepth(3).build().map(node);
        assertThat(client.sent).hasSize(3);
        assertThat(node.next.next.next).isNotNull();
        assertThat(node.next.next.next.next).isNull();
    }

    @Test
    void fetchSendsDistinctIdsAndExpressions() {
        var response = mapper(new UserSummaryProfile()).fetchAsync(USER,
                new io.github.quyvu01.jfxmap.response.MapRequest(
                        new ArrayList<>(Arrays.asList(ALICE.toString(), ALICE.toString(), null)),
                        Arrays.asList(null, null, "UserEmail")),
                Map.of()).join();

        assertThat(client.sent.getFirst().request().selectorIds()).containsExactly(ALICE.toString());
        assertThat(client.sent.getFirst().request().expressions()).containsExactly(null, "UserEmail");
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void anObjectWithoutProfileIsLeftAlone() {
        mapper(new UserSummaryProfile()).map(List.of("text", 42, new Object()));

        assertThat(client.sent).isEmpty();
    }
}
