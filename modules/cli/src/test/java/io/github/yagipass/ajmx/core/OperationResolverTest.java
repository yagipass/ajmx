package io.github.yagipass.ajmx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import org.junit.jupiter.api.Test;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class OperationResolverTest {

    private static MBeanOperationInfo op(String name, String... types) {
        MBeanParameterInfo[] params = new MBeanParameterInfo[types.length];
        for (int i = 0; i < types.length; i++) {
            params[i] = new MBeanParameterInfo("p" + i, types[i], "");
        }
        return new MBeanOperationInfo(name, "", params, "void", MBeanOperationInfo.ACTION);
    }

    private static final MBeanOperationInfo[] OPS = {
            op("invalidate", "java.lang.String"),
            op("invalidate", "java.lang.Object"),
            op("put", "java.lang.String", "int"),
            op("put", "java.lang.String", "long"),
            op("resize", "int"),
            op("resize", "java.lang.String"),
            op("clear"),
    };

    private static OperationResolver.Resolved resolve(String name, List<Object> args, List<String> signature) {
        return OperationResolver.resolve(OPS, name, args, signature);
    }

    @Test
    void jsonTypeSelectsTheOnlyFittingOverload() {
        OperationResolver.Resolved r = resolve("resize", List.of(new BigDecimal("3")), null);
        assertArrayEquals(new String[] { "int" }, r.signature());
        assertArrayEquals(new Object[] { 3 }, r.params());
        assertArrayEquals(new String[] { "java.lang.String" }, resolve("resize", List.of("3"), null).signature());
    }

    @Test
    void overlappingOverloadsAreAmbiguousInsteadOfGuessed() {
        AjmxException e = assertThrows(AjmxException.class, () -> resolve("invalidate", List.of("k"), null));
        assertEquals(ErrorCode.AMBIGUOUS_OPERATION, e.code());
        assertEquals(List.of(Map.of("signature", List.of("java.lang.Object")), Map.of("signature", List.of("java.lang.String"))),
                e.details().get("candidates"));
        assertEquals(ErrorCode.AMBIGUOUS_OPERATION,
                assertThrows(AjmxException.class, () -> resolve("put", List.of("k", new BigDecimal("1")), null)).code());
    }

    @Test
    void valueOutOfRangeForOneOverloadLeavesTheOther() {
        OperationResolver.Resolved r = resolve("put", List.of("k", new BigDecimal("9999999999")), null);
        assertArrayEquals(new String[] { "java.lang.String", "long" }, r.signature());
    }

    @Test
    void explicitSignatureWinsOverResolution() {
        OperationResolver.Resolved r = resolve("invalidate", List.of("k"), List.of("java.lang.String"));
        assertArrayEquals(new String[] { "java.lang.String" }, r.signature());
        AjmxException e = assertThrows(AjmxException.class, () -> resolve("invalidate", List.of("k"), List.of("int")));
        assertEquals(ErrorCode.OPERATION_NOT_FOUND, e.code());
        AjmxException count = assertThrows(AjmxException.class, () -> resolve("invalidate", List.of(), List.of("java.lang.String")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, count.code());
    }

    @Test
    void noArgumentOperation() {
        assertEquals(0, resolve("clear", List.of(), null).params().length);
    }

    @Test
    void unknownNameAndWrongArityAreNotFound() {
        assertEquals(ErrorCode.OPERATION_NOT_FOUND,
                assertThrows(AjmxException.class, () -> resolve("nope", List.of(), null)).code());
        assertEquals(ErrorCode.OPERATION_NOT_FOUND,
                assertThrows(AjmxException.class, () -> resolve("clear", List.of("x"), null)).code());
    }

    @Test
    void jsonObjectNestedInAnArrayMatchesNoOverloadSoNothingIsInvoked() {
        AjmxException e = assertThrows(AjmxException.class,
                () -> resolve("invalidate", List.of(List.of(Map.of("a", 1), "x")), null));
        assertEquals(ErrorCode.TYPE_CONVERSION_FAILED, e.code());
    }

    @Test
    void argumentsFittingNoOverloadAreAConversionFailure() {
        AjmxException e = assertThrows(AjmxException.class, () -> resolve("put", List.of("k", "v"), null));
        assertEquals(ErrorCode.TYPE_CONVERSION_FAILED, e.code());
    }
}
