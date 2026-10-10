package io.github.yagipass.ajmx.codec;

import com.google.errorprone.annotations.Var;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.json.Json;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;

public final class ValueDecoder {
  private enum Kind {
    BOOLEAN,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    CHAR,
    STRING,
    OBJECT_NAME,
    OBJECT,
    BIG_INTEGER,
    BIG_DECIMAL
  }

  private record TargetType(Kind kind, Class<?> type) {}

  private static final List<TargetType> TARGET_TYPES =
      List.of(
          new TargetType(Kind.BOOLEAN, boolean.class),
          new TargetType(Kind.BOOLEAN, Boolean.class),
          new TargetType(Kind.BYTE, byte.class),
          new TargetType(Kind.BYTE, Byte.class),
          new TargetType(Kind.SHORT, short.class),
          new TargetType(Kind.SHORT, Short.class),
          new TargetType(Kind.INT, int.class),
          new TargetType(Kind.INT, Integer.class),
          new TargetType(Kind.LONG, long.class),
          new TargetType(Kind.LONG, Long.class),
          new TargetType(Kind.FLOAT, float.class),
          new TargetType(Kind.FLOAT, Float.class),
          new TargetType(Kind.DOUBLE, double.class),
          new TargetType(Kind.DOUBLE, Double.class),
          new TargetType(Kind.CHAR, char.class),
          new TargetType(Kind.CHAR, Character.class),
          new TargetType(Kind.STRING, String.class),
          new TargetType(Kind.OBJECT_NAME, ObjectName.class),
          new TargetType(Kind.OBJECT, Object.class),
          new TargetType(Kind.BIG_INTEGER, BigInteger.class),
          new TargetType(Kind.BIG_DECIMAL, BigDecimal.class));

  private static final Map<String, TargetType> SCALARS =
      TARGET_TYPES.stream()
          .collect(Collectors.toUnmodifiableMap(t -> t.type().getName(), Function.identity()));

  private static final Map<String, TargetType> ARRAYS =
      TARGET_TYPES.stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  t -> t.type().arrayType().getName(), Function.identity()));

  private static final Set<String> NON_FINITE = Set.of("NaN", "Infinity", "-Infinity");

  private static final int MAX_INTEGER_DIGITS = 10_000;

  private ValueDecoder() {}

  public static @Nullable Object decodeParsingStrings(@Nullable Object input, String type) {
    return decode(input, type, true);
  }

  public static @Nullable Object decode(@Nullable Object input, String type) {
    return decode(input, type, false);
  }

  private static @Nullable Object decode(
      @Nullable Object input, String type, boolean parseStrings) {
    TargetType element = ARRAYS.get(type);
    if (element != null) {
      return array(input, type, element, parseStrings);
    }
    TargetType target = SCALARS.get(type);
    if (target == null) {
      throw unsupported(type);
    }
    if (input == null) {
      if (target.type().isPrimitive()) {
        throw failed(type, null);
      }
      return null;
    }
    @Var Object out;
    try {
      out = scalar(input, target.kind(), parseStrings);
    } catch (ArithmeticException | NumberFormatException | MalformedObjectNameException e) {
      out = null;
    }
    if (out == null) {
      throw failed(type, input);
    }
    return out;
  }

  private static @Nullable Object scalar(Object in, Kind kind, boolean parseStrings)
      throws MalformedObjectNameException {
    if (kind == Kind.OBJECT) {
      return object(in);
    }
    if (in instanceof Number n) {
      return fromNumber(decimal(n), kind);
    }
    if (in instanceof String s) {
      return fromString(s, kind, parseStrings);
    }
    return in instanceof Boolean && kind == Kind.BOOLEAN ? in : null;
  }

  private static @Nullable Object fromNumber(BigDecimal n, Kind kind) {
    return switch (kind) {
      case BYTE -> n.byteValueExact();
      case SHORT -> n.shortValueExact();
      case INT -> n.intValueExact();
      case LONG -> n.longValueExact();
      case FLOAT -> finite(n.floatValue());
      case DOUBLE -> finite(n.doubleValue());
      case BIG_INTEGER -> integer(n);
      case BIG_DECIMAL -> n;
      case BOOLEAN, CHAR, STRING, OBJECT_NAME, OBJECT -> null;
    };
  }

  private static @Nullable Object fromString(String s, Kind kind, boolean parseStrings)
      throws MalformedObjectNameException {
    return switch (kind) {
      case STRING, OBJECT -> s;
      case CHAR -> s.length() == 1 ? s.charAt(0) : null;
      case OBJECT_NAME -> new ObjectName(s);
      case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL ->
          parseStrings ? parse(s, kind) : null;
    };
  }

  private static @Nullable Object parse(String s, Kind kind) {
    return switch (kind) {
      case BOOLEAN ->
          switch (s.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
          };
      case BYTE -> Byte.parseByte(s);
      case SHORT -> Short.parseShort(s);
      case INT -> Integer.parseInt(s);
      case LONG -> Long.parseLong(s);
      case FLOAT ->
          NON_FINITE.contains(s) ? Float.valueOf(s) : finite(new BigDecimal(s).floatValue());
      case DOUBLE ->
          NON_FINITE.contains(s) ? Double.valueOf(s) : finite(new BigDecimal(s).doubleValue());
      case BIG_INTEGER -> new BigInteger(s);
      case BIG_DECIMAL -> new BigDecimal(s);
      case CHAR, STRING, OBJECT_NAME, OBJECT -> null;
    };
  }

  private static @Nullable Float finite(float f) {
    return Float.isFinite(f) ? f : null;
  }

  private static @Nullable Double finite(double d) {
    return Double.isFinite(d) ? d : null;
  }

  private static @Nullable Object object(Object in) {
    if (in instanceof String || in instanceof Boolean) {
      return in;
    }
    if (in instanceof Number n) {
      BigDecimal d = decimal(n);
      if (d.stripTrailingZeros().scale() <= 0) {
        BigInteger i = integer(d);
        return i.bitLength() < 64 ? (Object) i.longValue() : i;
      }
      return d.doubleValue();
    }
    if (in instanceof List<?> list) {
      @Nullable Object[] out = new Object[list.size()];
      for (int i = 0; i < out.length; i++) {
        Object element = list.get(i);
        if (element == null) {
          continue;
        }
        out[i] = object(element);
        if (out[i] == null) {
          return null;
        }
      }
      return out;
    }
    return null;
  }

  private static BigInteger integer(BigDecimal n) {
    BigDecimal d = n.stripTrailingZeros();
    if (d.scale() > 0 || d.precision() - d.scale() > MAX_INTEGER_DIGITS) {
      throw new ArithmeticException("Not an integer of at most " + MAX_INTEGER_DIGITS + " digits");
    }
    return d.toBigIntegerExact();
  }

  private static BigDecimal decimal(Number n) {
    return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
  }

  private static @Nullable Object array(
      @Nullable Object input, String type, TargetType element, boolean parseStrings) {
    if (input == null) {
      return null;
    }
    List<?> values =
        input instanceof List<?> l
            ? l
            : parseStrings && input instanceof String s ? jsonArray(s) : null;
    if (values == null) {
      throw failed(type, input);
    }
    Object array = Array.newInstance(element.type(), values.size());
    for (int i = 0; i < values.size(); i++) {
      try {
        Array.set(array, i, decode(values.get(i), element.type().getName(), parseStrings));
      } catch (AjmxException e) {
        throw e.withContext(Map.of("index", i));
      }
    }
    return array;
  }

  private static @Nullable List<?> jsonArray(String s) {
    try {
      return Json.parse(s) instanceof List<?> l ? l : null;
    } catch (Json.ParseException e) {
      return null;
    }
  }

  private static AjmxException failed(String type, @Nullable Object input) {
    return new AjmxException(ErrorCode.TYPE_CONVERSION_FAILED, "Failed to convert input value")
        .with("expected", type)
        .with("input", input instanceof String s ? s : Json.write(input));
  }

  private static AjmxException unsupported(String type) {
    return new AjmxException(ErrorCode.UNSUPPORTED_TYPE, "Input of this type is not supported")
        .with("class", type);
  }
}
