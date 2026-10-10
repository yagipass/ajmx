package io.github.yagipass.ajmx.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ValueDecoderTest {

  private static @Nullable Object lenient(@Nullable Object input, String type) {
    return ValueDecoder.decodeParsingStrings(input, type);
  }

  private static @Nullable Object strict(@Nullable Object input, String type) {
    return ValueDecoder.decode(input, type);
  }

  @Test
  void lenientParsesCommandLineTextIntoTheTargetType() throws Exception {
    assertEquals(true, lenient("TRUE", "boolean"));
    assertEquals((byte) -7, lenient("-7", "byte"));
    assertEquals((short) 300, lenient("300", "java.lang.Short"));
    assertEquals(20, lenient("20", "int"));
    assertEquals(9007199254740993L, lenient("9007199254740993", "long"));
    assertEquals(0.25f, lenient("0.25", "float"));
    assertEquals(1000.0, lenient("1e3", "double"));
    assertEquals('q', lenient("q", "char"));
    assertEquals("20", lenient("20", "java.lang.String"));
    assertEquals(new ObjectName("d:type=x"), lenient("d:type=x", "javax.management.ObjectName"));
    assertArrayEquals(new int[] {1, 2}, (int[]) lenient("[1,2]", "[I"));
    assertArrayEquals(
        new String[] {"a", null}, (String[]) lenient("[\"a\",null]", "[Ljava.lang.String;"));
  }

  @Test
  void strictKeepsJsonTypesApartSoOverloadsCanBeResolved() {
    assertEquals(5, strict(new BigDecimal("5"), "int"));
    assertEquals(5L, strict(new BigDecimal("5"), "long"));
    assertInvalid(() -> strict("5", "int"));
    assertInvalid(() -> strict(new BigDecimal("5"), "java.lang.String"));
    assertInvalid(() -> strict("true", "boolean"));
    assertEquals(true, strict(true, "java.lang.Boolean"));
  }

  @Test
  void numbersMustFitExactly() {
    assertInvalid(() -> strict(new BigDecimal("1.5"), "int"));
    assertInvalid(() -> strict(new BigDecimal("2147483648"), "int"));
    assertInvalid(() -> strict(new BigDecimal("128"), "byte"));
    assertInvalid(() -> strict(new BigDecimal("1e400"), "double"));
    assertEquals(1, strict(new BigDecimal("1.0"), "int"));
  }

  @Test
  void exponentCannotMakeAjmxComputeAndSendAHugeInteger() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> {
          for (String type : List.of("java.math.BigInteger", "java.lang.Object")) {
            assertInvalid(() -> strict(new BigDecimal("1e30000000"), type));
            assertInvalid(() -> strict(new BigDecimal("-1e10000"), type));
            assertInvalid(() -> strict(List.of(new BigDecimal("1e30000000")), "[L" + type + ";"));
          }
          assertInvalid(() -> strict(new BigDecimal("1.5e-30000000"), "java.math.BigInteger"));
          assertEquals(
              BigInteger.TEN.pow(9999).negate(),
              strict(new BigDecimal("-1e9999"), "java.math.BigInteger"));
          assertEquals(
              BigInteger.ZERO, strict(new BigDecimal("0e30000000"), "java.math.BigInteger"));
          assertEquals(0L, strict(new BigDecimal("0e30000000"), "java.lang.Object"));
        });
  }

  @Test
  void textThatOverflowsAFloatingTypeIsRejectedInsteadOfStoredAsInfinity() {
    assertInvalid(() -> lenient("1e50", "float"));
    assertInvalid(() -> lenient("-1e50", "java.lang.Float"));
    assertInvalid(() -> lenient("1e400", "double"));
    assertInvalid(() -> lenient("1.5f", "float"));
    assertInvalid(() -> lenient("0x1p3", "double"));
    assertEquals(Float.MAX_VALUE, lenient("3.4028235e38", "float"));
  }

  @Test
  void nonFiniteValuesAreAcceptedOnlyWhenWrittenAsAjmxPrintsThem() {
    assertEquals(Double.POSITIVE_INFINITY, lenient("Infinity", "double"));
    assertEquals(Float.NEGATIVE_INFINITY, lenient("-Infinity", "float"));
    assertTrue(Double.isNaN((Double) Objects.requireNonNull(lenient("NaN", "java.lang.Double"))));
    assertInvalid(() -> strict("Infinity", "double"));
  }

  @Test
  void objectParametersGetTheNaturalJavaType() {
    assertEquals("s", strict("s", "java.lang.Object"));
    assertEquals(5L, strict(new BigDecimal("5"), "java.lang.Object"));
    assertEquals(1.5, strict(new BigDecimal("1.5"), "java.lang.Object"));
    assertArrayEquals(
        new Object[] {"a", 1L},
        (Object[]) strict(List.of("a", new BigDecimal("1")), "java.lang.Object"));
    assertArrayEquals(
        new Object[] {null, "x"}, (Object[]) strict(Arrays.asList(null, "x"), "java.lang.Object"));
  }

  @Test
  void jsonObjectIsRejectedEvenInsideAnArrayInsteadOfBecomingNull() {
    assertInvalid(() -> strict(Map.of("a", 1), "java.lang.Object"));
    assertInvalid(() -> strict(List.of(Map.of("a", 1), "x"), "java.lang.Object"));
    assertInvalid(() -> strict(List.of(List.of("x", Map.of("a", 1))), "java.lang.Object"));
    assertInvalid(() -> strict(List.of(Map.of("a", 1), "x"), "[Ljava.lang.Object;"));
  }

  @Test
  void nullOnlyForReferenceTypes() {
    assertNull(strict(null, "java.lang.Integer"));
    assertInvalid(() -> strict(null, "int"));
  }

  @Test
  void failureNamesTheExpectedTypeAndTheInput() {
    AjmxException e = assertInvalid(() -> lenient("abc", "int"));
    assertEquals("int", e.details().get("expected"));
    assertEquals("abc", e.details().get("input"));
    AjmxException element = assertInvalid(() -> lenient("[1,\"x\"]", "[I"));
    assertEquals(1, element.details().get("index"));
  }

  @Test
  void typesThatOnlyExistInTheTargetAreUnsupported() {
    for (String type :
        List.of(
            "com.example.Mode",
            "javax.management.openmbean.CompositeData",
            "[[I",
            "[Lcom.example.X;")) {
      AjmxException e = assertThrows(AjmxException.class, () -> lenient("x", type), type);
      assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code(), type);
      assertEquals(type, e.details().get("class"));
    }
  }

  @Test
  void malformedArrayTypeIsNotReadAsTheArrayItResembles() {
    for (String type : List.of("[Lint;", "[Ljava.lang.String", "[Q", "[")) {
      AjmxException e = assertThrows(AjmxException.class, () -> lenient("[1]", type), type);
      assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code(), type);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("arrays")
  void arraysHaveTheExactClassTheSignatureNamesOrTheTargetRejectsThem(
      String type, String input, Object expected) {
    Object actual = Objects.requireNonNull(lenient(input, type));
    assertEquals(expected.getClass(), actual.getClass());
    assertTrue(
        Objects.deepEquals(expected, actual), () -> Arrays.deepToString(new Object[] {actual}));
  }

  static Stream<Arguments> arrays() throws Exception {
    return Stream.of(
        Arguments.of("[Z", "[true]", new boolean[] {true}),
        Arguments.of("[B", "[1]", new byte[] {1}),
        Arguments.of("[S", "[1]", new short[] {1}),
        Arguments.of("[I", "[1]", new int[] {1}),
        Arguments.of("[J", "[1]", new long[] {1}),
        Arguments.of("[F", "[1]", new float[] {1}),
        Arguments.of("[D", "[1]", new double[] {1}),
        Arguments.of("[C", "[\"c\"]", new char[] {'c'}),
        Arguments.of("[Ljava.lang.Boolean;", "[true]", new Boolean[] {true}),
        Arguments.of("[Ljava.lang.Byte;", "[1]", new Byte[] {1}),
        Arguments.of("[Ljava.lang.Short;", "[1]", new Short[] {1}),
        Arguments.of("[Ljava.lang.Integer;", "[1]", new Integer[] {1}),
        Arguments.of("[Ljava.lang.Long;", "[1]", new Long[] {1L}),
        Arguments.of("[Ljava.lang.Float;", "[1]", new Float[] {1f}),
        Arguments.of("[Ljava.lang.Double;", "[1]", new Double[] {1d}),
        Arguments.of("[Ljava.lang.Character;", "[\"c\"]", new Character[] {'c'}),
        Arguments.of("[Ljava.lang.String;", "[\"s\"]", new String[] {"s"}),
        Arguments.of(
            "[Ljavax.management.ObjectName;",
            "[\"d:k=v\"]",
            new ObjectName[] {new ObjectName("d:k=v")}),
        Arguments.of("[Ljava.math.BigInteger;", "[1]", new BigInteger[] {BigInteger.ONE}),
        Arguments.of("[Ljava.math.BigDecimal;", "[1]", new BigDecimal[] {BigDecimal.ONE}),
        Arguments.of("[Ljava.lang.Object;", "[1]", new Object[] {1L}));
  }

  private static AjmxException assertInvalid(Runnable r) {
    AjmxException e = assertThrows(AjmxException.class, r::run);
    assertEquals(ErrorCode.TYPE_CONVERSION_FAILED, e.code());
    return e;
  }
}
