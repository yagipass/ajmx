package io.github.yagipass.ajmx.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class JsonTest {

  @Test
  void objectsKeepInsertionOrderSoOutputIsDeterministic() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("z", 1);
    m.put("a", List.of(true, "x", Arrays.asList((Object) null)));
    assertEquals("{\"z\":1,\"a\":[true,\"x\",[null]]}", Json.write(m));
  }

  @Test
  void stringsAreEscapedIntoValidJsonAndUtf8() {
    assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.write("a\"b\\c\n\u0001"));
    assertEquals("\"日本\"", Json.write("日本"));
    String lone = Json.write("x\ud800y");
    assertEquals("\"x\\ud800y\"", lone);
    assertEquals(lone, new String(lone.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
  }

  @Test
  void nonFiniteNumbersBecomeStringsBecauseJsonHasNoNaN() {
    assertEquals(
        "[\"NaN\",\"Infinity\",1.5]",
        Json.write(List.of(Double.NaN, Float.POSITIVE_INFINITY, 1.5)));
  }

  @Test
  void sizeIsTheEncodedByteCountSoOutputLimitsAreExact() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put(
        "k\"ey",
        Arrays.asList(
            null, true, 1, 2L, 3.5, Float.NaN, Double.NEGATIVE_INFINITY, new BigDecimal("1e-7")));
    nested.put("text", "a\"b\\c\n\t\u0001 \u00e9 \u65e5\u672c \ud83d\ude00 lone \ud800 end \udc00");
    for (Object value :
        List.of(
            "",
            "plain",
            nested,
            List.of(nested, Map.of()),
            new BigInteger("123456789012345678901234567890"))) {
      assertEquals(
          Json.write(value).getBytes(StandardCharsets.UTF_8).length,
          Json.size(value),
          Json.write(value));
    }
  }

  @Test
  void parsedNumbersAreExactSoLargeLongsSurvive() {
    assertEquals(new BigDecimal("9007199254740993"), Json.parse("9007199254740993"));
    assertEquals(new BigDecimal("1.5e3"), Json.parse("1.5e3"));
  }

  @Test
  void parsesNestedDocuments() {
    Map<?, ?> doc =
        (Map<?, ?>)
            Objects.requireNonNull(
                Json.parse(" {\"requests\": [{\"id\": \"a\", \"n\": null, \"u\": \"\\u00e9\"}]} "));
    Map<?, ?> first =
        (Map<?, ?>) ((List<?>) Objects.requireNonNull(doc.get("requests"))).getFirst();
    assertEquals("a", first.get("id"));
    assertTrue(first.containsKey("n"));
    assertNull(first.get("n"));
    assertEquals("é", first.get("u"));
    assertEquals("\u00e9\u00C9", Json.parse("\"\\u00e9\\u00C9\""));
  }

  @Test
  void rejectsMalformedInputInsteadOfGuessing() {
    for (String bad :
        List.of(
            "",
            "[1,]",
            "{\"a\":1,\"a\":2}",
            "01",
            "\"unterminated",
            "[1] x",
            "{'a':1}",
            "nul",
            "1e99999999999",
            "[-1E-99999999999]",
            "\"\\u+041\"",
            "\"\\u-041\"",
            "\"\\u\uFF10\uFF10\uFF14\uFF11\"",
            "\"\\u004\"")) {
      assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
    }
  }
}
