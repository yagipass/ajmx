package io.github.yagipass.ajmx.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.errorprone.annotations.Var;

import io.github.yagipass.ajmx.json.Json;

final class ValueEncoderTest {

    private static String json(Object value) {
        return Json.write(new ValueEncoder(100, Long.MAX_VALUE).encode(value));
    }

    private static final CompositeType ROW;

    static {
        try {
            ROW = new CompositeType("row", "row", new String[] { "value", "key" }, new String[] { "v", "k" },
                    new OpenType<?>[] { SimpleType.INTEGER, SimpleType.STRING });
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeData row(String key, int value) throws Exception {
        return new CompositeDataSupport(ROW, Map.of("key", key, "value", value));
    }

    @Test
    void scalars() throws Exception {
        List<Object> values = Arrays.asList(null, "s", true, 1, 2L, 3.5, 'c', TimeUnit.SECONDS, new ObjectName("d:k=v"), Double.NaN);
        assertEquals("[null,\"s\",true,1,2,3.5,\"c\",\"SECONDS\",\"d:k=v\",\"NaN\"]", json(values));
    }

    @Test
    void compositeDataKeysAreSortedWhateverTheTypeDeclares() throws Exception {
        assertEquals("{\"key\":\"a\",\"value\":1}", json(row("a", 1)));
    }

    @Test
    void tabularRowsAreSortedSoOutputDoesNotDependOnHashOrder() throws Exception {
        TabularDataSupport table = new TabularDataSupport(new TabularType("t", "t", ROW, new String[] { "key" }));
        table.put(row("b", 2));
        table.put(row("a", 1));
        table.put(row("c", 3));
        assertEquals("[{\"key\":\"a\",\"value\":1},{\"key\":\"b\",\"value\":2},{\"key\":\"c\",\"value\":3}]", json(table));
    }

    @Test
    void setsAndMapsAreSortedWhateverTheirIterationOrder() {
        assertEquals("[\"a\",\"b\",\"c\"]", json(new LinkedHashSet<>(List.of("c", "a", "b"))));
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("b", 2);
        map.put("a", 1);
        assertEquals("{\"a\":1,\"b\":2}", json(map));
    }

    @Test
    void arraysAndAttributes() {
        assertEquals("[1,2]", json(new int[] { 1, 2 }));
        assertEquals("[true]", json(new boolean[] { true }));
        assertEquals("{\"$base64\":\"AQ==\"}", json(new byte[] { 1 }));
        assertEquals("[1]", json(new short[] { 1 }));
        assertEquals("[1]", json(new long[] { 1 }));
        assertEquals("[1.5,\"NaN\"]", json(new float[] { 1.5f, Float.NaN }));
        assertEquals("[1.5,\"Infinity\"]", json(new double[] { 1.5, Double.POSITIVE_INFINITY }));
        assertEquals("[\"a\"]", json(new char[] { 'a' }));
        assertEquals("[\"x\",null]", json(new String[] { "x", null }));
        assertEquals("[{\"name\":\"A\",\"value\":1}]", json(new AttributeList(List.of(new Attribute("A", 1)))));
    }

    @Test
    void attributeListHoldingOtherValuesIsStillShownBecauseAnMBeanCanReturnOne() {
        AttributeList list = new AttributeList();
        list.add(new Attribute("A", 1));
        list.add("raw");
        assertEquals("[{\"name\":\"A\",\"value\":1},\"raw\"]", json(list));
    }

    @Test
    void collectionsOverTheLimitAreMarkedNotSilentlyCut() {
        ValueEncoder encoder = new ValueEncoder(2, Long.MAX_VALUE);
        Object encoded = encoder.encode(new long[] { 1, 2, 3 });
        assertEquals("{\"$truncated\":true,\"$total\":3,\"$items\":[1,2]}", Json.write(encoded));
        assertTrue(encoder.truncated());
    }

    @Test
    void onlyTheElementsWithinTheLimitAreReadSoAHugeValueCostsNoMoreThanASmallOne() {
        int[] read = { 0 };
        Collection<Integer> large = new AbstractCollection<>() {
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return read[0] < size();
                    }

                    @Override
                    public Integer next() {
                        return read[0]++;
                    }
                };
            }

            @Override
            public int size() {
                return 1_000_000;
            }
        };
        assertEquals("{\"$truncated\":true,\"$total\":1000000,\"$items\":[0,1]}", Json.write(new ValueEncoder(2, Long.MAX_VALUE).encode(large)));
        assertEquals(2, read[0]);
    }

    @Test
    void truncatedSetsAndMapsKeepTheirFirstEntriesInSortedOrderWhateverTheirIterationOrder() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("b", 2);
        map.put("a", 1);
        assertEquals("{\"$truncated\":true,\"$total\":2,\"$entries\":{\"a\":1}}", Json.write(new ValueEncoder(1, Long.MAX_VALUE).encode(map)));
        assertEquals("{\"$truncated\":true,\"$total\":3,\"$items\":[\"a\",\"b\"]}",
                Json.write(new ValueEncoder(2, Long.MAX_VALUE).encode(new LinkedHashSet<>(List.of("c", "a", "b")))));
    }

    @Test
    void withinTheLimitNothingIsMarked() {
        ValueEncoder encoder = new ValueEncoder(3, Long.MAX_VALUE);
        encoder.encode(List.of(1, 2, 3));
        assertFalse(encoder.truncated());
    }

    @Test
    @SuppressWarnings("UnnecessaryStringBuilder")
    void unknownTypesFallBackToTypeAndStringWithoutReflection() {
        assertEquals("{\"$type\":\"java.lang.StringBuilder\",\"$string\":\"abc\"}", json(new StringBuilder("abc")));
    }

    @Test
    void valueContainingItselfIsCutWhereItRefersBackSoItCannotBlowUp() {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);
        assertEquals("{\"self\":{\"$truncated\":true,\"$type\":\"java.util.HashMap\"}}", json(map));

        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        loop.add(loop);
        ValueEncoder encoder = new ValueEncoder(100, Long.MAX_VALUE);
        String cut = "{\"$truncated\":true,\"$type\":\"java.util.ArrayList\"}";
        assertEquals("[" + cut + "," + cut + "]", Json.write(encoder.encode(loop)));
        assertTrue(encoder.truncated());
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void valueSharingItsPartsStopsOnceItHoldsMoreValuesThanCanBePrintedInsteadOfExpandingEveryPath() {
        @Var Object shared = "leaf";
        for (int i = 0; i < 40; i++) {
            shared = Arrays.asList(shared, shared);
        }
        ValueEncoder encoder = new ValueEncoder(100, 10_000);
        Object encoded = encoder.encode(shared);
        assertTrue(encoder.truncated());
        assertTrue(Json.size(encoded) > 10_000, "a value cut this way must still exceed the budget, so it is never printed");
    }

    @Test
    void setElementsLeftOutByTheLimitDoNotUseUpTheValuesLeftForTheRest() {
        Set<Object> set = new LinkedHashSet<>();
        set.add(chain(9, 4));
        set.add(chain(8, 4));
        set.add(List.of(1));
        set.add(List.of(2));
        assertEquals("[{\"$truncated\":true,\"$total\":4,\"$items\":[[1],[2]]},[3]]",
                Json.write(new ValueEncoder(2, 18).encode(List.of(set, List.of(3)))));
    }

    private static Object chain(int leaf, int levels) {
        @Var Object v = leaf;
        for (int i = 0; i < levels; i++) {
            v = List.of(leaf, v);
        }
        return v;
    }

    @Test
    void valueSharedWithoutACycleIsShownWhereverItAppears() {
        List<Object> shared = List.of(1);
        ValueEncoder encoder = new ValueEncoder(100, 5);
        assertEquals("[[1],[1]]", Json.write(encoder.encode(List.of(shared, shared))));
        assertFalse(encoder.truncated());
    }
}
