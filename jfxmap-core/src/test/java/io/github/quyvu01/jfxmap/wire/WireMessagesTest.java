package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class WireMessagesTest {
    @Test
    void writesARequestAsFxMapDoes() {
        var json = new String(WireMessages.writeRequest(
                new MapRequest(List.of("1", "2"), Arrays.asList(null, "Email"))), UTF_8);

        assertThat(json).isEqualTo("{\"SelectorIds\":[\"1\",\"2\"],\"Expressions\":[null,\"Email\"]}");
    }

    @Test
    void readsARequestWhateverTheCaseOfItsNames() {
        var request = WireMessages.readRequest(
                "{\"selectorIds\":[\"1\"],\"EXPRESSIONS\":[null,\"Country.Name\"]}".getBytes(UTF_8));

        assertThat(request.selectorIds()).containsExactly("1");
        assertThat(request.expressions()).containsExactly(null, "Country.Name");
    }

    @Test
    void aRequestWithoutMembersIsEmpty() {
        var request = WireMessages.readRequest("{}".getBytes(UTF_8));

        assertThat(request.selectorIds()).isEmpty();
        assertThat(request.expressions()).isEmpty();
    }

    @Test
    void aRequestThatIsNotOneIsRejected() {
        assertThatThrownBy(() -> WireMessages.readRequest("[1]".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireMessages.readRequest("{\"SelectorIds\":\"1\"}".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireMessages.readRequest("not json".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireMessages.readRequest(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesASuccessAsFxMapDoes() {
        var response = new ItemsResponse(
                List.of(new DataResponse("1", List.of(new ValueResponse(null, "\"Ann\""),
                        new ValueResponse("Email", null)))),
                List.of(new ExpressionError("Bad(", "syntax")));

        var json = new String(WireMessages.writeSuccess(response), UTF_8);

        assertThat(json).isEqualTo("{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\",\"Values\":["
                + "{\"Expression\":null,\"Value\":\"\\\"Ann\\\"\"},{\"Expression\":\"Email\",\"Value\":null}]}],"
                + "\"Errors\":[{\"Expression\":\"Bad(\",\"Message\":\"syntax\"}]},\"Fault\":null}");
    }

    @Test
    void aSuccessReadsBackToTheSameResponse() {
        var response = new ItemsResponse(
                List.of(new DataResponse("1", List.of(new ValueResponse(null, "\"Ann\"")))),
                List.of(new ExpressionError("Bad(", "syntax")));

        assertThat(WireMessages.readResult(WireMessages.writeSuccess(response))).isEqualTo(response);
    }

    @Test
    void readsASuccessThatLeavesNullsOut() {
        var response = WireMessages.readResult(
                "{\"isSuccess\":true,\"data\":{\"items\":[{\"id\":\"7\",\"values\":[{\"value\":\"1\"}]}]}}"
                        .getBytes(UTF_8));

        assertThat(response.items()).containsExactly(new DataResponse("7", List.of(new ValueResponse(null, "1"))));
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void aFailureCarriesTheExceptionAndItsCauses() {
        var failure = new CompletionException(new IllegalStateException("outer", new ArithmeticException("inner")));

        var json = WireMessages.writeFailure(failure);

        assertThat(new String(json, UTF_8)).contains("\"IsSuccess\":false", "\"Data\":null", "\"FaultId\"",
                "\"Timestamp\"", "\"Host\"", "\"ProcessId\"");
        assertThatThrownBy(() -> WireMessages.readResult(json))
                .isInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessage("The owner of the distributed key failed: java.lang.IllegalStateException: outer")
                .asInstanceOf(type(DistributedMapException.ReceivedFault.class))
                .satisfies(fault -> {
                    assertThat(fault.faultId()).isNotBlank();
                    assertThat(fault.exceptions()).extracting(DistributedMapException.ReceivedFault.ExceptionInfo::exceptionType)
                            .containsExactly("java.lang.IllegalStateException", "java.lang.ArithmeticException");
                    assertThat(fault.exceptions().getFirst().stackTrace()).contains("WireMessagesTest");
                });
    }

    @Test
    void aFailureWithoutMessageUsesTheTypeName() {
        assertThatThrownBy(() -> WireMessages.readResult(WireMessages.writeFailure(new NullPointerException())))
                .hasMessageEndingWith("java.lang.NullPointerException: java.lang.NullPointerException");
    }

    @Test
    void readsAFaultOfFxMap() {
        var json = "{\"IsSuccess\":false,\"Data\":null,\"Fault\":{\"FaultId\":\"f1\",\"Exceptions\":[{"
                   + "\"ExceptionType\":\"System.TimeoutException\",\"Message\":\"Request timeout for UserOf\","
                   + "\"StackTrace\":null,\"Source\":null}]}}";

        assertThatThrownBy(() -> WireMessages.readResult(json.getBytes(UTF_8)))
                .isInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("System.TimeoutException: Request timeout for UserOf");
    }

    @Test
    void aFaultWithoutExceptionsStillFails() {
        assertThatThrownBy(() -> WireMessages.readResult("{\"IsSuccess\":false}".getBytes(UTF_8)))
                .isInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessage("The owner of the distributed key failed.");
    }

    @Test
    void aReplyThatIsNotAnObjectIsRejected() {
        assertThatThrownBy(() -> WireMessages.readResult("true".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
