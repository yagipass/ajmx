package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.list;
import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.yagipass.ajmx.json.Json;

final class OperationsIT {
    private static final String CACHE = "ajmxtest:type=Cache";
    private static final String SETTINGS = "ajmxtest:type=Settings";
    private static final String QUIRKY = "ajmxtest:type=Quirky";
    private static final String ARRAY_ECHO = "ajmxtest:type=ArrayEcho";
    private static final String EVOLVING = "ajmxtest:type=Evolving";
    private static final String SLEEPY = "ajmxtest:type=Sleepy";

    private static TargetJvm target;

    @BeforeAll
    static void start() throws Exception {
        target = TargetJvm.start();
    }

    @AfterAll
    static void stop() throws Exception {
        target.close();
    }

    private static Ajmx.Result ajmx(String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("--pid", target.pidArg()));
        all.addAll(List.of(args));
        return Ajmx.run(all.toArray(String[]::new));
    }

    private static Map<String, Object> attributes(Ajmx.Result r) {
        return map(r.result().get("attributes"));
    }

    @Test
    void searchFindsMBeansSortedSoRepeatedCallsAgree() throws Exception {
        Ajmx.Result r = ajmx("search", "ajmxtest:*");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(List.of(ARRAY_ECHO, CACHE, EVOLVING, QUIRKY, SETTINGS, SLEEPY), r.result().get("items"));
        assertEquals(6L, number(r.result().get("returned")));
        assertEquals(false, r.result().get("truncated"));
    }

    @Test
    void searchWithoutPatternListsEverythingSoAnAgentCanStartBlind() throws Exception {
        Ajmx.Result r = ajmx("search");
        assertTrue(list(r.result().get("items")).contains("java.lang:type=Memory"), r.stdout());
    }

    @Test
    void searchReportsTruncationInsteadOfSilentlyDroppingNames() throws Exception {
        Ajmx.Result r = ajmx("--limit", "3", "search", "java.lang:*");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(3, list(r.result().get("items")).size());
        assertEquals(true, r.result().get("truncated"));
    }

    @Test
    void searchRejectsMalformedPatternWithoutConnecting() throws Exception {
        Ajmx.run("--pid", Ajmx.NOT_A_JVM, "search", "no-colon").assertError("INVALID_ARGUMENT", 2);
    }

    @Test
    void describeGivesEverythingNeededToBuildReadWriteAndInvoke() throws Exception {
        Map<String, Object> r = ajmx("describe", CACHE).result();
        assertEquals(CACHE, r.get("mbean"));
        assertEquals("ajmxtest.TestTarget$Cache", r.get("className"));

        Map<String, Object> size = attribute(r, "Size");
        assertEquals(Map.of("name", "Size", "type", "int", "readable", true, "writable", false), size);
        assertEquals(true, attribute(r, "MaxConnections").get("writable"));
        assertEquals("[Ljava.lang.String;", attribute(r, "Tags").get("type"));

        List<Object> puts = list(r.get("operations")).stream().filter(o -> Objects.equals(map(o).get("name"), "put")).toList();
        assertEquals(2, puts.size());
        assertEquals("int", map(list(map(puts.get(0)).get("signature")).get(1)).get("type"));
        assertEquals("long", map(list(map(puts.get(1)).get("signature")).get(1)).get("type"));
    }

    @Test
    void describeSortsAttributesAndOperationsWhateverTheOrderOfTheMBeanInfo() throws Exception {
        Map<String, Object> r = ajmx("describe", QUIRKY).result();
        assertEquals(List.of("Null", "Pairs", "Raw", "Size"), list(r.get("attributes")).stream().map(a -> map(a).get("name")).toList());
        List<String> operations = list(r.get("operations")).stream().map(Ajmx::map)
                .map(o -> o.get("name") + list(o.get("signature")).stream().map(p -> (String) map(p).get("type")).toList().toString())
                .toList();
        assertEquals(List.of("put[java.lang.String, int]", "put[java.lang.String, long]", "reset[]"), operations);
    }

    @Test
    void describeOfUnknownMBeanIsNotFound() throws Exception {
        ajmx("describe", "ajmxtest:type=Nope").assertError("MBEAN_NOT_FOUND", 4);
    }

    private static Map<String, Object> attribute(Map<String, Object> describe, String name) {
        return list(describe.get("attributes")).stream().map(Ajmx::map)
                .filter(a -> name.equals(a.get("name"))).findFirst().orElseThrow();
    }

    @Test
    void readMultipleAttributesInRequestOrder() throws Exception {
        Ajmx.Result r = ajmx("read", "java.lang:type=Threading", "ThreadCount", "PeakThreadCount", "DaemonThreadCount");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(List.of("ThreadCount", "PeakThreadCount", "DaemonThreadCount"), List.copyOf(attributes(r).keySet()));
    }

    @Test
    void compositeDataBecomesAnObjectWithSortedKeys() throws Exception {
        Map<String, Object> heap = map(attributes(ajmx("read", "java.lang:type=Memory", "HeapMemoryUsage")).get("HeapMemoryUsage"));
        assertEquals(List.of("committed", "init", "max", "used"), List.copyOf(heap.keySet()));
        assertTrue(number(heap.get("used")) > 0);

        Map<String, Object> stats = map(attributes(ajmx("read", SETTINGS, "Stats")).get("Stats"));
        assertEquals(7L, number(stats.get("hits")));
        assertEquals(3L, number(stats.get("misses")));
    }

    @Test
    void tabularDataBecomesRowsSortedByKey() throws Exception {
        List<Object> rows = list(attributes(ajmx("read", SETTINGS, "Limits")).get("Limits"));
        assertEquals(List.of("a", "b", "c"), rows.stream().map(row -> map(row).get("key")).toList());
    }

    @Test
    void arraysCharsAndMXBeanEnumsAreReadable() throws Exception {
        Map<String, Object> a = attributes(ajmx("read", CACHE, "Tags", "Letter", "Size"));
        assertTrue(a.get("Tags") instanceof List, a.toString());
        assertTrue(a.get("Letter") instanceof String, a.toString());
        assertTrue(List.of("FAST", "SAFE").contains(attributes(ajmx("read", SETTINGS, "Mode")).get("Mode")));
    }

    @Test
    void unknownMBeanAndAttributeAreNotFound() throws Exception {
        ajmx("read", "ajmxtest:type=Nope", "X").assertError("MBEAN_NOT_FOUND", 4);
        Ajmx.Result r = ajmx("read", CACHE, "Nope").assertError("ATTRIBUTE_NOT_FOUND", 4);
        assertEquals("Nope", r.details().get("attribute"));
    }

    @Test
    void valueOfATargetOnlyClassIsReportedWithItsClassName() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Custom").assertError("UNSUPPORTED_TYPE", 5);
        assertEquals("ajmxtest.TestTarget$CustomValue", r.details().get("class"));
        assertEquals(null, r.details().get("executed"), "a read changes nothing");
    }

    @Test
    void oneUnreadableAttributeDoesNotHideTheOthers() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Size", "Custom", "Nope");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(42L, number(attributes(r).get("Size")));
        Map<String, Object> errors = map(r.result().get("errors"));
        assertEquals("UNSUPPORTED_TYPE", map(errors.get("Custom")).get("code"));
        assertEquals("ATTRIBUTE_NOT_FOUND", map(errors.get("Nope")).get("code"));
    }

    @Test
    void whenEveryAttributeFailsEachErrorIsKeptWhateverTheOrder() throws Exception {
        for (List<String> order : List.of(List.of("Custom", "Nope"), List.of("Nope", "Custom"))) {
            List<String> args = new ArrayList<>(List.of("read", CACHE));
            args.addAll(order);
            Ajmx.Result r = ajmx(args.toArray(String[]::new));
            assertEquals(7, r.exitCode(), r.stdout());
            assertEquals(Map.of(), attributes(r));
            Map<String, Object> errors = map(r.result().get("errors"));
            assertEquals(order, List.copyOf(errors.keySet()));
            assertEquals("UNSUPPORTED_TYPE", map(errors.get("Custom")).get("code"));
            assertEquals("ATTRIBUTE_NOT_FOUND", map(errors.get("Nope")).get("code"));
        }
    }

    @Test
    void malformedBulkReadOfADynamicMBeanFallsBackToReadingEachAttribute() throws Exception {
        Ajmx.Result raw = ajmx("read", QUIRKY, "Size", "Raw");
        assertEquals(0, raw.exitCode(), raw.stdout());
        assertEquals(Json.parse("{\"Size\":42,\"Raw\":\"raw\"}"), attributes(raw));

        Ajmx.Result none = ajmx("read", QUIRKY, "Size", "Null");
        assertEquals(0, none.exitCode(), none.stdout());
        assertEquals(Json.parse("{\"Size\":42,\"Null\":\"null\"}"), attributes(none));

        Object pairs = attributes(ajmx("read", QUIRKY, "Pairs")).get("Pairs");
        assertEquals(Json.parse("[{\"name\":\"a\",\"value\":1},\"b\"]"), pairs);
    }

    @Test
    void valueTheTargetCannotSerializeIsAnAttributeErrorNotAConnectionFailure() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Size", "Handle");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(42L, number(attributes(r).get("Size")));
        Map<String, Object> handle = map(map(r.result().get("errors")).get("Handle"));
        assertEquals("UNSUPPORTED_TYPE", handle.get("code"));
        assertEquals(false, handle.get("retryable"));
        assertEquals("ajmxtest.TestTarget$Handle", map(handle.get("details")).get("class"));
    }

    @Test
    void valueTheTargetFailsToSendIsAnErrorOfThatAttributeOnly() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Size", "Unsendable");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(42L, number(attributes(r).get("Size")));
        assertEquals("CONNECTION_FAILED", map(map(r.result().get("errors")).get("Unsendable")).get("code"));

        Ajmx.Result alone = ajmx("read", CACHE, "Unsendable").assertError("CONNECTION_FAILED", 3);
        assertEquals("Unsendable", alone.details().get("attribute"));
    }

    @Test
    void readThatLosesTheConnectionOnEveryAttributeFailsAsAConnectionFailure() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Unsendable", "Unsendable2").assertError("CONNECTION_FAILED", 3);
        assertEquals("Unsendable", r.details().get("attribute"));
    }

    @Test
    void jdkValuesTheJvmCanReadAreReadableByTheNativeBinaryToo() throws Exception {
        Map<String, Object> a = attributes(ajmx("read", CACHE, "QualifiedName", "Grid", "Matrix", "JdkLambda"));
        assertEquals("{urn:ajmx}local", map(a.get("QualifiedName")).get("$string"));
        assertEquals(Json.parse("[[1,2],[3]]"), normalize(a.get("Grid")));
        assertEquals(Json.parse("[[1.5],[2.5,3.5]]"), normalize(a.get("Matrix")));
        assertTrue(((String) Objects.requireNonNull(map(a.get("JdkLambda")).get("$type"))).startsWith("java.util.Map$Entry$$Lambda"), a.toString());
        List<Object> grid = list(attributes(ajmx("read", SETTINGS, "StatsGrid")).get("StatsGrid"));
        assertEquals(7L, number(map(list(grid.get(1)).get(0)).get("hits")));
    }

    @Test
    void lambdaOfATargetOnlyClassIsReportedWithThatClass() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "TargetLambda").assertError("UNSUPPORTED_TYPE", 5);
        assertEquals("ajmxtest.TestTarget$Cache", r.details().get("class"));
    }

    @Test
    void slowAttributeTimesOutAloneWithoutHidingTheOthers() throws Exception {
        Ajmx.Result r = ajmx("--timeout", "1s", "read", CACHE, "Size", "SlowValue");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(42L, number(attributes(r).get("Size")));
        assertEquals("TIMEOUT", map(map(r.result().get("errors")).get("SlowValue")).get("code"));

        Ajmx.Result alone = ajmx("--timeout", "1s", "read", CACHE, "SlowValue").assertError("TIMEOUT", 6);
        assertEquals("SlowValue", alone.details().get("attribute"));
    }

    @Test
    void slowAttributesTimeOutTogetherSoAReadEndsWithinTwiceTheTimeoutWhateverTheOrder() throws Exception {
        long start = System.nanoTime();
        Ajmx.Result r = ajmx("--timeout", "2s", "read", SLEEPY, "Slow1", "Slow2", "Slow3", "Slow4", "Fast");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(Json.parse("{\"Fast\":42}"), attributes(r));
        Map<String, Object> errors = map(r.result().get("errors"));
        assertEquals(List.of("Slow1", "Slow2", "Slow3", "Slow4"), List.copyOf(errors.keySet()));
        errors.values().forEach(e -> assertEquals("TIMEOUT", map(e).get("code")));
        assertTrue(elapsedMs < 7000, "took " + elapsedMs + " ms");
    }

    @Test
    void largeCollectionsAreCappedByLimitAndMarked() throws Exception {
        Ajmx.Result r = ajmx("--limit", "10", "read", CACHE, "LargeArray");
        assertEquals(7, r.exitCode(), r.stdout());
        Map<String, Object> value = map(attributes(r).get("LargeArray"));
        assertEquals(true, value.get("$truncated"));
        assertEquals(1000L, number(value.get("$total")));
        assertEquals(10, list(value.get("$items")).size());
        assertEquals(true, r.result().get("truncated"));
    }

    @Test
    void binaryIsPrintedWholeAsBase64BecauseAChunkCutByLimitIsUseless() throws Exception {
        Ajmx.Result r = ajmx("--limit", "1", "invoke", CACHE, "bytes", "--args", "[3]");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(Map.of("$base64", "AQID"), r.result().get("returnValue"));
        assertEquals(null, r.result().get("truncated"));
    }

    @Test
    void valueContainingItselfIsCutAtTheCycleInsteadOfExhaustingMemory() throws Exception {
        Ajmx.Result r = ajmx("read", CACHE, "Size", "SelfReference");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(42L, number(attributes(r).get("Size")));
        Map<String, Object> cut = Map.of("$truncated", true, "$type", "java.util.ArrayList");
        assertEquals(List.of(cut, cut), attributes(r).get("SelfReference"));
        assertEquals(true, r.result().get("truncated"));
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void valueSharingItsPartsFailsLikeAnyOutputOverMaxBytesInsteadOfExpandingEveryPath() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "10000", "read", CACHE, "Shared").assertError("OUTPUT_TRUNCATED", 7);
        assertTrue(number(r.details().get("outputBytes")) > 10000);
    }

    @Test
    void outputLargerThanMaxBytesIsRefusedRatherThanCutMidJson() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "10000", "read", CACHE, "LargeText").assertError("OUTPUT_TRUNCATED", 7);
        assertTrue(r.stdout().length() <= 10000);
        assertTrue(number(r.details().get("outputBytes")) > 400_000);
    }

    @Test
    void invokeWhoseResultExceedsMaxBytesSaysItWasExecuted() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "512", "invoke", CACHE, "greet", "--args",
                "[\"" + "x".repeat(1000) + "\"]").assertError("OUTPUT_TRUNCATED", 7);
        assertEquals(true, r.details().get("executed"));
        Ajmx.Result read = ajmx("--max-bytes", "10000", "read", CACHE, "LargeText").assertError("OUTPUT_TRUNCATED", 7);
        assertEquals(null, read.details().get("executed"));
    }

    @Test
    void binaryOverMaxBytesFailsInsteadOfPrintingACutChunkAndSaysTheOperationRan() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "1000", "invoke", CACHE, "bytes", "--args", "[1000]")
                .assertError("OUTPUT_TRUNCATED", 7);
        assertEquals(true, r.details().get("executed"));
    }

    @Test
    void valueThatBarelyFitsInMemoryStillGivesExactlyOneJsonDocument() throws Exception {
        assumeFalse(Ajmx.nativeMode(), "sets the heap size of a JVM");
        for (String heap : List.of("192m", "224m", "256m", "288m", "320m", "352m")) {
            List<String> command = new ArrayList<>(Ajmx.command("--pid", target.pidArg(), "read", CACHE, "HugeText"));
            command.add(1, "-Xmx" + heap);
            Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS));
            assertTrue(stdout.endsWith("\n") && Json.parse(stdout.strip()) instanceof Map, heap + ": " + stdout);
        }
    }

    @Test
    void searchShrinksToMaxBytesKeepingValidJson() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "600", "search");
        assertEquals(7, r.exitCode(), r.stdout());
        assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 600);
        assertEquals(true, r.result().get("truncated"));
        assertEquals((long) list(r.result().get("items")).size(), number(r.result().get("returned")));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            "Enabled|false|false",
            "Name|renamed|\"renamed\"",
            "ByteValue|-7|-7",
            "ShortValue|300|300",
            "MaxConnections|20|20",
            "LongValue|9007199254740993|9007199254740993",
            "FloatValue|0.25|0.25",
            "DoubleValue|1e3|1000.0",
            "Letter|q|\"q\"",
            "Ref|java.lang:type=Memory|\"java.lang:type=Memory\"",
            "Tags|[\"x\",\"y\"]|[\"x\",\"y\"]",
            "Numbers|[7,8,9]|[7,8,9]",
    })
    void writeConvertsTextToTheAttributeTypeFromMBeanInfo(String attribute, String input, String expectedJson) throws Exception {
        Ajmx.Result w = ajmx("write", CACHE, attribute + "=" + input);
        assertEquals(0, w.exitCode(), w.stdout());
        Object readBack = attributes(ajmx("read", CACHE, attribute)).get(attribute);
        assertEquals(Json.parse(expectedJson), normalize(readBack));
    }

    private static @Nullable Object normalize(@Nullable Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof List<?> l) {
            return l.stream().map(OperationsIT::normalize).toList();
        }
        return v;
    }

    @Test
    void writeWhoseEchoedValueIsCutByLimitSaysSoLikeReadAndInvoke() throws Exception {
        Ajmx.Result r = ajmx("--limit", "1", "write", CACHE, "Tags=[\"x\",\"y\"]");
        assertEquals(7, r.exitCode(), r.stdout());
        assertEquals(true, r.result().get("truncated"));
        assertEquals(2L, number(map(r.result().get("value")).get("$total")));
        assertEquals(List.of("x", "y"), attributes(ajmx("read", CACHE, "Tags")).get("Tags"));
    }

    @Test
    void numberTooLargeForAFloatAttributeIsRejectedAndNothingIsWritten() throws Exception {
        Object before = attributes(ajmx("read", CACHE, "FloatValue")).get("FloatValue");
        ajmx("write", CACHE, "FloatValue=1e50").assertError("TYPE_CONVERSION_FAILED", 2);
        assertEquals(before, attributes(ajmx("read", CACHE, "FloatValue")).get("FloatValue"));
    }

    @Test
    void mxBeanEnumIsWrittenAsItsName() throws Exception {
        assertEquals(0, ajmx("write", SETTINGS, "Mode=FAST").exitCode());
        assertEquals("FAST", attributes(ajmx("read", SETTINGS, "Mode")).get("Mode"));
    }

    @Test
    void readOnlyAttributeIsNotWritableRatherThanNotFound() throws Exception {
        ajmx("write", CACHE, "Size=1").assertError("ATTRIBUTE_NOT_WRITABLE", 2);
    }

    @Test
    void typeConversionFailureNamesExpectedTypeAndInput() throws Exception {
        Ajmx.Result r = ajmx("write", CACHE, "MaxConnections=abc").assertError("TYPE_CONVERSION_FAILED", 2);
        assertEquals("int", r.details().get("expected"));
        assertEquals("abc", r.details().get("input"));
    }

    @Test
    void standardMBeanEnumCannotBeWrittenWithoutItsClass() throws Exception {
        Ajmx.Result r = ajmx("write", CACHE, "Mode=SAFE").assertError("UNSUPPORTED_TYPE", 5);
        assertEquals("ajmxtest.TestTarget$Mode", r.details().get("class"));
    }

    @Test
    void invokeWithoutArgumentsRunsTheOperation() throws Exception {
        long before = number(attributes(ajmx("read", CACHE, "ClearCount")).get("ClearCount"));
        Ajmx.Result r = ajmx("invoke", CACHE, "clear");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(null, r.result().get("returnValue"));
        assertEquals(before + 1, number(attributes(ajmx("read", CACHE, "ClearCount")).get("ClearCount")));
    }

    @Test
    void invokeWithArguments() throws Exception {
        Ajmx.Result r = ajmx("invoke", CACHE, "greet", "--args", "[\"agent\"]");
        assertEquals("hello agent", r.result().get("returnValue"));
        Ajmx.Result sum = ajmx("invoke", CACHE, "sum", "--args", "[[1,2,3]]");
        assertEquals(6L, number(sum.result().get("returnValue")));
    }

    @Test
    void ambiguousOverloadIsNotInvokedAndListsCandidates() throws Exception {
        Ajmx.Result r = ajmx("invoke", CACHE, "invalidate", "--args", "[\"user:123\"]")
                .assertError("AMBIGUOUS_OPERATION", 4);
        assertEquals(List.of(Map.of("signature", List.of("java.lang.Object")), Map.of("signature", List.of("java.lang.String"))),
                r.details().get("candidates"));
    }

    @Test
    void explicitSignatureSelectsTheOverload() throws Exception {
        Ajmx.Result r = ajmx("invoke", CACHE, "invalidate", "--signature", "java.lang.String",
                "--args", "[\"users\"]");
        assertEquals("String:users", r.result().get("returnValue"));
        Ajmx.Result put = ajmx("invoke", CACHE, "put", "--signature", "java.lang.String,long",
                "--args", "[\"k\",5]");
        assertEquals("long:k=5", put.result().get("returnValue"));
        assertEquals(List.of("java.lang.String", "long"), put.result().get("signature"));
    }

    @Test
    void overloadIsResolvedFromJsonValuesWhenOnlyOneFits() throws Exception {
        ajmx("invoke", CACHE, "put", "--args", "[\"k\",5]").assertError("AMBIGUOUS_OPERATION", 4);
        ajmx("invoke", CACHE, "put", "--args", "[\"k\",\"5\"]").assertError("TYPE_CONVERSION_FAILED", 2);
        Ajmx.Result r = ajmx("invoke", CACHE, "put", "--args", "[\"k\",9999999999]");
        assertEquals("long:k=9999999999", r.result().get("returnValue"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[Z|[true]", "[B|[1]", "[S|[1]", "[I|[1]", "[J|[1]", "[F|[1.5]", "[D|[1.5]", "[C|[\"c\"]",
            "[Ljava.lang.Boolean;|[true]", "[Ljava.lang.Byte;|[1]", "[Ljava.lang.Short;|[1]", "[Ljava.lang.Integer;|[1]",
            "[Ljava.lang.Long;|[1]", "[Ljava.lang.Float;|[1.5]", "[Ljava.lang.Double;|[1.5]", "[Ljava.lang.Character;|[\"c\"]",
            "[Ljava.lang.String;|[\"s\"]", "[Ljavax.management.ObjectName;|[\"d:k=v\"]", "[Ljava.math.BigInteger;|[1]",
            "[Ljava.math.BigDecimal;|[1.5]", "[Ljava.lang.Object;|[\"s\"]" })
    void arrayArgumentsReachTheTargetAsTheExactClassTheSignatureNames(String type, String array) throws Exception {
        Ajmx.Result r = ajmx("invoke", ARRAY_ECHO, "classOf", "--signature", type, "--args", "[" + array + "]");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(type, r.result().get("returnValue"));
    }

    @Test
    void unknownOperationAndWrongArityAreNotFound() throws Exception {
        ajmx("invoke", CACHE, "nope").assertError("OPERATION_NOT_FOUND", 4);
        Ajmx.Result r = ajmx("invoke", CACHE, "greet").assertError("OPERATION_NOT_FOUND", 4);
        assertNotNull(r.details().get("candidates"));
    }

    @Test
    void exceptionFromTheMBeanIsReportedWithClassAndMessage() throws Exception {
        Ajmx.Result r = ajmx("invoke", CACHE, "failRuntime").assertError("REMOTE_EXCEPTION", 5);
        assertEquals("java.lang.IllegalStateException", r.details().get("exceptionClass"));
        assertEquals("bad state", r.details().get("exceptionMessage"));
    }

    @Test
    void hugeExceptionMessageFromTheTargetStaysWithinMaxBytes() throws Exception {
        Ajmx.Result r = ajmx("--max-bytes", "512", "invoke", CACHE, "failVerbose")
                .assertError("REMOTE_EXCEPTION", 5);
        assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 512, r.stdout());
        assertEquals("java.lang.IllegalStateException", r.details().get("exceptionClass"));
        assertEquals(true, r.details().get("truncated"));
    }

    @Test
    void exceptionOfATargetOnlyClassIsReportedWithItsClassName() throws Exception {
        Ajmx.Result r = ajmx("invoke", CACHE, "fail").assertError("UNSUPPORTED_TYPE", 5);
        assertEquals("ajmxtest.TestTarget$CustomException", r.details().get("class"));
        assertEquals("unknown", r.details().get("executed"));
    }

    @Test
    void invokeThatRanButWhoseResultCannotBeReadSaysItMayHaveRun() throws Exception {
        long before = clearCount();
        Ajmx.Result r = ajmx("invoke", CACHE, "touch", "--args", "[\"custom\"]")
                .assertError("UNSUPPORTED_TYPE", 5);
        assertEquals("ajmxtest.TestTarget$CustomValue", r.details().get("class"));
        assertEquals(false, r.error().get("retryable"));
        assertEquals("unknown", r.details().get("executed"), "the operation ran, so repeating it may apply it twice");
        assertEquals(before + 1, clearCount());
    }

    @Test
    void invokeWhoseResultCannotBeEncodedSaysItWasExecuted() throws Exception {
        long before = clearCount();
        Ajmx.Result r = ajmx("invoke", CACHE, "touch", "--args", "[\"loop\"]")
                .assertError("INTERNAL_ERROR", 1);
        assertEquals("java.lang.StackOverflowError", r.details().get("exceptionClass"));
        assertEquals(true, r.details().get("executed"), "the operation ran, so repeating it applies it twice");
        assertEquals(before + 1, clearCount());
    }

    private static long clearCount() throws Exception {
        return number(attributes(ajmx("read", CACHE, "ClearCount")).get("ClearCount"));
    }

    @Test
    void timedOutInvokeIsNotRetryableBecauseItMayStillBeRunning() throws Exception {
        long start = System.nanoTime();
        Ajmx.Result r = ajmx("--timeout", "1s", "invoke", CACHE, "sleep", "--args", "[20000]")
                .assertError("TIMEOUT", 6);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "must not wait for the operation");
        assertEquals(false, r.error().get("retryable"));
        assertEquals("unknown", r.details().get("executed"));
    }

    @Test
    void timedOutReadIsRetryableBecauseItChangesNothing() throws Exception {
        Ajmx.Result r = ajmx("--timeout", "1s", "read", CACHE, "SlowValue").assertError("TIMEOUT", 6);
        assertEquals(true, r.error().get("retryable"));
    }

    @Test
    void invokeArgumentsMustBeAJsonArray() throws Exception {
        ajmx("invoke", CACHE, "greet", "--args", "{\"a\":1}").assertError("INVALID_ARGUMENT", 2);
        ajmx("invoke", CACHE, "greet", "--args", "[").assertError("INVALID_ARGUMENT", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = { "invoke " + CACHE + " failRuntime", "invoke " + CACHE + " fail", "read " + CACHE + " Size Custom" })
    void errorsNeverLeakStackTracesToStdout(String command) throws Exception {
        Ajmx.Result r = ajmx(("--debug " + command).split(" "));
        List<String> frames = r.stderr().lines().filter(line -> line.startsWith("\tat ")).map(line -> line.substring(4)).toList();
        assertFalse(frames.isEmpty(), "--debug must print the trace to stderr: " + r.stderr());
        assertTrue(strings(r.json()).noneMatch(s -> frames.stream().anyMatch(s::contains)), r.stdout());
    }

    private static Stream<String> strings(Object json) {
        if (json instanceof String s) {
            return Stream.of(s);
        }
        if (json instanceof Map<?, ?> m) {
            return Stream.concat(m.keySet().stream(), m.values().stream()).flatMap(OperationsIT::strings);
        }
        if (json instanceof List<?> l) {
            return l.stream().flatMap(OperationsIT::strings);
        }
        return Stream.empty();
    }
}
