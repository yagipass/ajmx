package io.github.yagipass.ajmx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.error.Execution;

final class BatchTest {

    @Test
    void unexpectedFailureOfOneRequestNeitherStopsNorHidesTheOthers() {
        Batch batch = batch(entry("a", Op.READ), entry("npe", Op.READ), entry("soe", Op.READ), entry("b", Op.READ));
        List<Object> executed = new ArrayList<>();
        Outcome outcome = batch.run(r -> {
            executed.add(id(r));
            return switch (id(r)) {
                case "npe" -> throw new NullPointerException();
                case "soe" -> throw new StackOverflowError();
                default -> Outcome.of(Map.of("done", id(r)));
            };
        });

        assertEquals(List.of("a", "npe", "soe", "b"), executed);
        assertTrue(outcome.partial());
        List<?> items = (List<?>) outcome.result().get("items");
        assertEquals(Map.of("done", "a"), item(items, 0).get("result"));
        assertEquals("java.lang.NullPointerException", details(item(items, 1)).get("exceptionClass"));
        assertEquals("java.lang.StackOverflowError", details(item(items, 2)).get("exceptionClass"));
        assertEquals(Map.of("done", "b"), item(items, 3).get("result"));
    }

    @Test
    void failuresKeepTheirPathInTheOutputSoDebugCanTellWhichTraceIsWhich() {
        AjmxException attribute = new AjmxException(ErrorCode.ATTRIBUTE_NOT_FOUND, "attribute");
        AjmxException request = new AjmxException(ErrorCode.MBEAN_NOT_FOUND, "request");
        Outcome outcome = batch(entry("a", Op.READ), entry("b", Op.READ), entry("c", Op.READ)).run(r -> switch (id(r)) {
            case "a" -> Outcome.of(Map.of(), Map.of(".errors[\"X\"]", attribute), Execution.NOT_EXECUTED);
            case "b" -> throw request;
            default -> Outcome.of(Map.of());
        });
        assertEquals(List.of(".items[0].result.errors[\"X\"]", ".items[1].error"), List.copyOf(outcome.failuresByPath().keySet()));
        assertSame(attribute, outcome.failuresByPath().get(".items[0].result.errors[\"X\"]"));
        assertSame(request, outcome.failuresByPath().get(".items[1].error"));
    }

    @Test
    void onlyAMutationThatMayStillBeRunningHoldsBackTheMutationsAfterIt() {
        Batch batch = batch(entry("lookup", Op.WRITE), entry("w1", Op.WRITE), entry("slow", Op.INVOKE), entry("w2", Op.WRITE),
                entry("r", Op.READ));
        List<Object> executed = new ArrayList<>();
        Outcome outcome = batch.run(r -> {
            executed.add(id(r));
            return switch (id(r)) {
                case "lookup" -> throw new AjmxException(ErrorCode.TIMEOUT, "getMBeanInfo timed out");
                case "slow" -> throw new AjmxException(ErrorCode.TIMEOUT, "invoke timed out").withExecution(Execution.UNKNOWN);
                default -> Outcome.of(Map.of());
            };
        });

        assertEquals(List.of("lookup", "w1", "slow", "r"), executed);
        Map<?, ?> skipped = (Map<?, ?>) item((List<?>) outcome.result().get("items"), 3).get("error");
        assertEquals("SKIPPED", skipped.get("code"));
        assertEquals(2, ((Map<?, ?>) skipped.get("details")).get("stillRunningIndex"));
    }

    @Test
    void aMutationThatLostItsConnectionMayStillBeRunningSoItHoldsBackTheMutationsAfterIt() {
        AjmxException lost = new AjmxException(ErrorCode.CONNECTION_FAILED, "connection lost").withExecution(Execution.UNKNOWN);
        assertEquals(List.of("first", "r"), requestsRunAfter(lost));
    }

    @Test
    void aMutationWhoseReplyCouldNotBeReadHasReturnedSoTheMutationsAfterItRun() {
        AjmxException unreadable = new AjmxException(ErrorCode.UNSUPPORTED_TYPE, "unreadable reply").withExecution(Execution.UNKNOWN);
        assertEquals(List.of("first", "w", "r"), requestsRunAfter(unreadable));
    }

    private static List<Object> requestsRunAfter(AjmxException failure) {
        List<Object> executed = new ArrayList<>();
        batch(entry("first", Op.INVOKE), entry("w", Op.WRITE), entry("r", Op.READ)).run(r -> {
            executed.add(id(r));
            if (id(r).equals("first")) {
                throw failure;
            }
            return Outcome.of(Map.of());
        });
        return executed;
    }

    @Test
    void aBatchReportsAChangeToTheTargetOnlyIfAWriteOrInvokeMadeOne() {
        Function<Request, Outcome> executor = r -> switch (id(r)) {
            case "notWritable" -> throw new AjmxException(ErrorCode.ATTRIBUTE_NOT_WRITABLE, "not writable");
            case "unencodable" -> throw new AjmxException(ErrorCode.INTERNAL_ERROR, "encoding failed").withExecution(Execution.EXECUTED);
            case "unknown" -> throw new AjmxException(ErrorCode.TIMEOUT, "timed out").withExecution(Execution.UNKNOWN);
            default -> Outcome.of(Map.of(), Map.of(), r.op().mutating() ? Execution.EXECUTED : Execution.NOT_EXECUTED);
        };
        assertEquals(Execution.NOT_EXECUTED, batch(entry("read", Op.READ), entry("notWritable", Op.WRITE)).run(executor).execution());
        assertEquals(Execution.EXECUTED, batch(entry("read", Op.READ), entry("write", Op.WRITE)).run(executor).execution());
        assertEquals(Execution.EXECUTED, batch(entry("write", Op.WRITE), entry("read", Op.READ)).run(executor).execution(),
                "a later read does not undo the change");
        assertEquals(Execution.EXECUTED, batch(entry("read", Op.READ), entry("unencodable", Op.INVOKE)).run(executor).execution(),
                "the change was made although its result was lost");
        assertEquals(Execution.NOT_EXECUTED, batch(entry("read", Op.READ), entry("unknown", Op.INVOKE)).run(executor).execution(),
                "a change that may not have happened is not reported as made");
        assertEquals(Outcome.Shape.BATCH, batch().run(executor).shape());
    }

    @Test
    void invalidRequestRejectsTheWholeBatchBeforeAnythingRuns() {
        String clear = "{\"id\": \"ok\", \"op\": \"invoke\", \"mbean\": \"d:k=v\", \"operation\": \"clear\"}";
        Map<String, String> byField = new LinkedHashMap<>();
        byField.put("{\"id\": \"x\", \"op\": \"invoke\", \"mbean\": \"d:k=v\", \"operation\": \"clear\", \"arguments\": [\"k\"]}", "arguments");
        byField.put("{\"id\": \"x\", \"op\": \"search\", \"patern\": \"d:*\"}", "patern");
        byField.put("{\"id\": \"x\", \"op\": \"read\", \"mbean\": \"d:k=v\", \"attributes\": \"A\"}", "attributes");
        byField.put("{\"id\": \"x\", \"op\": \"read\", \"mbean\": \"d:k=v\", \"attributes\": []}", "attributes");
        byField.put("{\"id\": \"x\", \"op\": \"write\", \"mbean\": \"d:k=v\", \"attribute\": \"A\"}", "value");
        byField.put("{\"id\": \"x\", \"op\": \"describe\"}", "mbean");
        byField.put("{\"id\": \"x\"}", "op");
        for (Map.Entry<String, String> bad : byField.entrySet()) {
            AjmxException e = assertThrows(AjmxException.class, () -> Batch.parse(clear + "\n" + bad.getKey()), bad.getKey());
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code(), bad.getKey());
            assertEquals(2, e.details().get("line"), bad.getKey());
            assertEquals("x", e.details().get("id"), bad.getKey());
            assertEquals(bad.getValue(), e.details().get("field"), bad.getKey());
        }
        for (String bad : List.of("{\"op\": \"frobnicate\"}", "{\"op\": \"describe\", \"mbean\": \"no-colon\"}",
                "{\"op\": \"read\", \"mbean\": \"d:*\", \"attributes\": [\"A\"]}", "[{\"op\": \"ping\"}]",
                "{\"requests\": [{\"op\": \"ping\"}]}")) {
            AjmxException e = assertThrows(AjmxException.class, () -> Batch.parse(bad), bad);
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code(), bad);
        }
    }

    @Test
    void unknownFieldOrOpErrorListsWhatIsAllowedUnderTheSameKeySoEveryTypoIsFixedTheSameWay() {
        AjmxException field = assertThrows(AjmxException.class, () -> Batch.parse("{\"op\": \"search\", \"patern\": \"d:*\"}"));
        assertEquals(List.of("id", "op", "pattern"), field.details().get("allowed"));
        AjmxException op = assertThrows(AjmxException.class, () -> Batch.parse("{\"op\": \"raed\"}"));
        assertEquals(List.of("ping", "search", "describe", "read", "write", "invoke"), op.details().get("allowed"));
    }

    @Test
    void eachNonBlankLineIsOneRequestSoHeredocsAndCrlfFilesRunAsWritten() {
        Batch batch = Batch.parse("\n" + readLine("a") + "\r\n  \r\n" + readLine("b") + "\n");
        List<Object> executed = new ArrayList<>();
        batch.run(r -> {
            executed.add(id(r));
            return Outcome.of(Map.of());
        });
        assertEquals(List.of("a", "b"), executed);
    }

    @Test
    void anInvalidLineIsReportedByItsLineInTheInputSoBlankLinesDoNotShiftIt() {
        AjmxException json = assertThrows(AjmxException.class, () -> Batch.parse(readLine("a") + "\n\n{\"id\": \"b\", \"op\":"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, json.code());
        assertEquals("Invalid JSON", json.getMessage());
        assertEquals(3, json.details().get("line"));
        AjmxException request = assertThrows(AjmxException.class, () -> Batch.parse("\n\n{\"id\": \"x\", \"op\": \"raed\"}"));
        assertEquals(3, request.details().get("line"));
        AjmxException spanning = assertThrows(AjmxException.class, () -> Batch.parse("{\"id\": \"a\",\n \"op\": \"ping\"}"));
        assertEquals("Invalid JSON", spanning.getMessage(), "a request may not span lines");
        assertEquals(1, spanning.details().get("line"));
    }

    private static String readLine(String id) {
        return "{\"id\": \"" + id + "\", \"op\": \"read\", \"mbean\": \"test:id=" + id + "\", \"attributes\": [\"A\"]}";
    }

    private static Batch batch(Batch.Entry... entries) {
        return new Batch(List.of(entries));
    }

    private static Batch.Entry entry(String id, Op op) {
        String mbean = "test:id=" + id;
        Request request = switch (op) {
            case READ -> Request.read(mbean, List.of("A"));
            case WRITE -> Request.write(mbean, "A", 1);
            case INVOKE -> Request.invoke(mbean, "op", List.of(), null);
            case PING, SEARCH, DESCRIBE -> throw new IllegalArgumentException(op.token());
        };
        return new Batch.Entry(id, request);
    }

    private static String id(Request request) {
        ObjectName mbean = switch (request) {
            case Request.Read r -> r.mbean();
            case Request.Write w -> w.mbean();
            case Request.Invoke i -> i.mbean();
            case Request.Ping p -> throw new IllegalArgumentException(p.toString());
            case Request.Search s -> throw new IllegalArgumentException(s.toString());
            case Request.Describe d -> throw new IllegalArgumentException(d.toString());
        };
        return mbean.getKeyProperty("id");
    }

    private static Map<?, ?> item(List<?> items, int i) {
        return (Map<?, ?>) items.get(i);
    }

    private static Map<?, ?> details(Map<?, ?> item) {
        Map<?, ?> error = (Map<?, ?>) item.get("error");
        assertEquals("INTERNAL_ERROR", error.get("code"));
        return (Map<?, ?>) error.get("details");
    }
}
