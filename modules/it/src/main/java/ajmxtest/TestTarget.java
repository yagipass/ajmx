package ajmxtest;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.ImmutableDescriptor;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.xml.namespace.QName;

import com.google.errorprone.annotations.Var;

public final class TestTarget {
    public enum Mode {
        FAST, SAFE
    }

    public static final class CustomValue implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String value;

        CustomValue(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "CustomValue(" + value + ")";
        }
    }

    public static final class Handle {
        @Override
        public String toString() {
            return "Handle";
        }
    }

    public static final class Unsendable implements Serializable {
        private static final long serialVersionUID = 1L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            throw new IllegalStateException("Unsendable cannot be serialized");
        }
    }

    public static final class CustomException extends Exception {
        private static final long serialVersionUID = 1L;

        CustomException(String message) {
            super(message);
        }
    }

    public interface CacheMBean {
        int getSize();

        boolean isEnabled();

        void setEnabled(boolean v);

        String getName();

        void setName(String v);

        byte getByteValue();

        void setByteValue(byte v);

        short getShortValue();

        void setShortValue(short v);

        int getMaxConnections();

        void setMaxConnections(int v);

        long getLongValue();

        void setLongValue(long v);

        float getFloatValue();

        void setFloatValue(float v);

        double getDoubleValue();

        void setDoubleValue(double v);

        char getLetter();

        void setLetter(char v);

        ObjectName getRef();

        void setRef(ObjectName v);

        String[] getTags();

        void setTags(String[] v);

        int[] getNumbers();

        void setNumbers(int[] v);

        Mode getMode();

        void setMode(Mode v);

        CustomValue getCustom();

        Object getHandle();

        Object getUnsendable();

        Object getUnsendable2();

        int[] getLargeArray();

        String getLargeText();

        String getHugeText();

        Object getDeepValue();

        Object getSelfReference();

        Object getShared();

        long getSlowValue() throws InterruptedException;

        QName getQualifiedName();

        Integer[][] getGrid();

        float[][] getMatrix();

        Comparator<Map.Entry<String, Integer>> getJdkLambda();

        Object getTargetLambda();

        int getClearCount();

        void clear();

        Object touch(String result);

        String greet(String name);

        String invalidate(String key);

        String invalidate(Object key);

        String put(String key, int value);

        String put(String key, long value);

        long sum(int[] values);

        byte[] bytes(int length);

        void sleep(long millis) throws InterruptedException;

        void fail() throws CustomException;

        void failRuntime();

        void failVerbose();
    }

    public static final class Cache implements CacheMBean {
        private volatile boolean enabled = true;
        private volatile String name = "cache";
        private volatile byte byteValue = 1;
        private volatile short shortValue = 2;
        private volatile int maxConnections = 10;
        private volatile long longValue = 3L;
        private volatile float floatValue = 1.5f;
        private volatile double doubleValue = 2.5d;
        private volatile char letter = 'x';
        private volatile ObjectName ref;
        private volatile String[] tags = { "a", "b" };
        private volatile int[] numbers = { 1, 2 };
        private volatile Mode mode = Mode.FAST;
        private final AtomicInteger clearCount = new AtomicInteger();

        @Override
        public int getSize() {
            return 42;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean v) {
            enabled = v;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String v) {
            name = v;
        }

        @Override
        public byte getByteValue() {
            return byteValue;
        }

        @Override
        public void setByteValue(byte v) {
            byteValue = v;
        }

        @Override
        public short getShortValue() {
            return shortValue;
        }

        @Override
        public void setShortValue(short v) {
            shortValue = v;
        }

        @Override
        public int getMaxConnections() {
            return maxConnections;
        }

        @Override
        public void setMaxConnections(int v) {
            maxConnections = v;
        }

        @Override
        public long getLongValue() {
            return longValue;
        }

        @Override
        public void setLongValue(long v) {
            longValue = v;
        }

        @Override
        public float getFloatValue() {
            return floatValue;
        }

        @Override
        public void setFloatValue(float v) {
            floatValue = v;
        }

        @Override
        public double getDoubleValue() {
            return doubleValue;
        }

        @Override
        public void setDoubleValue(double v) {
            doubleValue = v;
        }

        @Override
        public char getLetter() {
            return letter;
        }

        @Override
        public void setLetter(char v) {
            letter = v;
        }

        @Override
        public ObjectName getRef() {
            return ref;
        }

        @Override
        public void setRef(ObjectName v) {
            ref = v;
        }

        @Override
        public String[] getTags() {
            return tags;
        }

        @Override
        public void setTags(String[] v) {
            tags = v;
        }

        @Override
        public int[] getNumbers() {
            return numbers;
        }

        @Override
        public void setNumbers(int[] v) {
            numbers = v;
        }

        @Override
        public Mode getMode() {
            return mode;
        }

        @Override
        public void setMode(Mode v) {
            mode = v;
        }

        @Override
        public CustomValue getCustom() {
            return new CustomValue("hello");
        }

        @Override
        public Object getHandle() {
            return new Handle();
        }

        @Override
        public Object getUnsendable() {
            return new Unsendable();
        }

        @Override
        public Object getUnsendable2() {
            return new Unsendable();
        }

        @Override
        public int[] getLargeArray() {
            int[] a = new int[1000];
            for (int i = 0; i < a.length; i++) {
                a[i] = i;
            }
            return a;
        }

        @Override
        public String getLargeText() {
            StringBuilder sb = new StringBuilder();
            while (sb.length() < 400_000) {
                sb.append("0123456789");
            }
            return sb.toString();
        }

        @Override
        public String getHugeText() {
            char[] text = new char[100_000_000];
            for (int i = 0; i < text.length; i++) {
                text[i] = (char) ('0' + i % 10);
            }
            return new String(text);
        }

        @Override
        public Object getDeepValue() {
            @Var Object value = "leaf";
            for (int i = 0; i < 100_000; i++) {
                value = new Object[] { value };
            }
            return value;
        }

        @Override
        public Object getSelfReference() {
            List<Object> list = new ArrayList<Object>();
            list.add(list);
            list.add(list);
            return list;
        }

        @Override
        public Object getShared() {
            @Var Object value = "leaf";
            for (int i = 0; i < 40; i++) {
                List<Object> pair = new ArrayList<Object>();
                pair.add(value);
                pair.add(value);
                value = pair;
            }
            return value;
        }

        @Override
        public long getSlowValue() throws InterruptedException {
            Thread.sleep(3000);
            return 1;
        }

        @Override
        public QName getQualifiedName() {
            return new QName("urn:ajmx", "local");
        }

        @Override
        public Integer[][] getGrid() {
            return new Integer[][] { { 1, 2 }, { 3 } };
        }

        @Override
        public float[][] getMatrix() {
            return new float[][] { { 1.5f }, { 2.5f, 3.5f } };
        }

        @Override
        public Comparator<Map.Entry<String, Integer>> getJdkLambda() {
            return Map.Entry.comparingByKey();
        }

        @Override
        public Object getTargetLambda() {
            return (Runnable & Serializable) () -> {
            };
        }

        @Override
        public int getClearCount() {
            return clearCount.get();
        }

        @Override
        public void clear() {
            clearCount.incrementAndGet();
        }

        @Override
        public Object touch(String result) {
            clearCount.incrementAndGet();
            if (Objects.equals(result, "custom")) {
                return new CustomValue(result);
            }
            if (Objects.equals(result, "unsendable")) {
                return new Unsendable();
            }
            if (Objects.equals(result, "loop")) {
                AbstractMap.SimpleEntry<String, Object> loop = new AbstractMap.SimpleEntry<String, Object>(result, null);
                loop.setValue(loop);
                return loop;
            }
            return result;
        }

        @Override
        public String greet(String who) {
            return "hello " + who;
        }

        @Override
        public String invalidate(String key) {
            return "String:" + key;
        }

        @Override
        public String invalidate(Object key) {
            return "Object:" + key;
        }

        @Override
        public String put(String key, int value) {
            return "int:" + key + "=" + value;
        }

        @Override
        public String put(String key, long value) {
            return "long:" + key + "=" + value;
        }

        @Override
        public long sum(int[] values) {
            @Var long s = 0;
            for (int v : values) {
                s += v;
            }
            return s;
        }

        @Override
        public byte[] bytes(int length) {
            byte[] b = new byte[length];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (i + 1);
            }
            return b;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        @Override
        public void fail() throws CustomException {
            throw new CustomException("boom");
        }

        @Override
        public void failRuntime() {
            throw new IllegalStateException("bad state");
        }

        @Override
        public void failVerbose() {
            StringBuilder message = new StringBuilder("verbose failure ");
            while (message.length() < 100_000) {
                message.append("0123456789");
            }
            throw new IllegalStateException(message.toString());
        }
    }

    public static final class Stats {
        public long getHits() {
            return 7;
        }

        public long getMisses() {
            return 3;
        }
    }

    public interface SettingsMXBean {
        Mode getMode();

        void setMode(Mode m);

        Stats getStats();

        Map<String, Integer> getLimits();

        List<List<Stats>> getStatsGrid();
    }

    public static final class Settings implements SettingsMXBean {
        private volatile Mode mode = Mode.SAFE;

        @Override
        public Mode getMode() {
            return mode;
        }

        @Override
        public void setMode(Mode m) {
            mode = m;
        }

        @Override
        public Stats getStats() {
            return new Stats();
        }

        @Override
        public Map<String, Integer> getLimits() {
            Map<String, Integer> m = new LinkedHashMap<String, Integer>();
            m.put("b", 2);
            m.put("a", 1);
            m.put("c", 3);
            return m;
        }

        @Override
        public List<List<Stats>> getStatsGrid() {
            return Arrays.asList(Arrays.asList(new Stats(), new Stats()), Collections.singletonList(new Stats()));
        }
    }

    public static final class ArrayEcho implements DynamicMBean {
        private static final String[] ARRAY_TYPES = { "[Z", "[B", "[S", "[I", "[J", "[F", "[D", "[C",
                "[Ljava.lang.Boolean;", "[Ljava.lang.Byte;", "[Ljava.lang.Short;", "[Ljava.lang.Integer;",
                "[Ljava.lang.Long;", "[Ljava.lang.Float;", "[Ljava.lang.Double;", "[Ljava.lang.Character;",
                "[Ljava.lang.String;", "[Ljavax.management.ObjectName;", "[Ljava.math.BigInteger;",
                "[Ljava.math.BigDecimal;", "[Ljava.lang.Object;" };

        private final AtomicInteger infoCalls = new AtomicInteger();

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (!Objects.equals(attribute, "InfoCalls")) {
                throw new AttributeNotFoundException(attribute);
            }
            return infoCalls.get();
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String name : attributes) {
                if (Objects.equals(name, "InfoCalls")) {
                    list.add(new Attribute(name, infoCalls.get()));
                }
            }
            return list;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute.getName());
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
            if (!Objects.equals(actionName, "classOf") || params.length != 1 || params[0] == null) {
                throw new ReflectionException(new NoSuchMethodException(actionName));
            }
            return params[0].getClass().getName();
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            infoCalls.incrementAndGet();
            MBeanAttributeInfo[] attributes = { new MBeanAttributeInfo("InfoCalls", "int", "", true, false, false) };
            MBeanOperationInfo[] operations = new MBeanOperationInfo[ARRAY_TYPES.length];
            for (int i = 0; i < ARRAY_TYPES.length; i++) {
                operations[i] = operation("classOf", ARRAY_TYPES[i]);
            }
            return new MBeanInfo(ArrayEcho.class.getName(), "", attributes, null, operations, null,
                    new ImmutableDescriptor("immutableInfo=true"));
        }
    }

    public static final class Evolving implements DynamicMBean {
        private final AtomicInteger generation = new AtomicInteger();

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (!Objects.equals(attribute, "Generation")) {
                throw new AttributeNotFoundException(attribute);
            }
            return generation.get();
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String name : attributes) {
                if (Objects.equals(name, "Generation")) {
                    list.add(new Attribute(name, generation.get()));
                }
            }
            return list;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute.getName());
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
            if (Objects.equals(actionName, "evolve")) {
                return generation.incrementAndGet();
            }
            int current = generation.get();
            if (("generation" + current).equals(actionName)) {
                return current;
            }
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            MBeanAttributeInfo[] attributes = { new MBeanAttributeInfo("Generation", "int", "", true, false, false) };
            MBeanOperationInfo[] operations = { operation("evolve"), operation("generation" + generation.get()) };
            return new MBeanInfo(Evolving.class.getName(), "", attributes, null, operations, null);
        }
    }

    public static final class Sleepy implements DynamicMBean {
        private static Object value(String attribute) {
            if (Objects.equals(attribute, "Fast")) {
                return 42;
            }
            if (!attribute.startsWith("Slow")) {
                return null;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            Object value = value(attribute);
            if (value == null) {
                throw new AttributeNotFoundException(attribute);
            }
            return value;
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String name : attributes) {
                Object value = value(name);
                if (value != null) {
                    list.add(new Attribute(name, value));
                }
            }
            return list;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute.getName());
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            MBeanAttributeInfo[] attributes = { new MBeanAttributeInfo("Fast", "int", "", true, false, false),
                    new MBeanAttributeInfo("Slow1", "int", "", true, false, false),
                    new MBeanAttributeInfo("Slow2", "int", "", true, false, false),
                    new MBeanAttributeInfo("Slow3", "int", "", true, false, false),
                    new MBeanAttributeInfo("Slow4", "int", "", true, false, false) };
            return new MBeanInfo(Sleepy.class.getName(), "", attributes, null, null, null);
        }
    }

    public static final class Quirky implements DynamicMBean {
        private static Object value(String attribute) {
            if (Objects.equals(attribute, "Size")) {
                return 42;
            }
            if (Objects.equals(attribute, "Raw") || Objects.equals(attribute, "Null")) {
                return attribute.toLowerCase(Locale.ROOT);
            }
            if (Objects.equals(attribute, "Pairs")) {
                AttributeList pairs = new AttributeList();
                pairs.add(new Attribute("a", 1));
                pairs.add("b");
                return pairs;
            }
            return null;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            Object value = value(attribute);
            if (value == null) {
                throw new AttributeNotFoundException(attribute);
            }
            return value;
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String name : attributes) {
                if (Objects.equals(name, "Null")) {
                    return null;
                }
                Object value = value(name);
                if (value != null) {
                    list.add(Objects.equals(name, "Raw") ? value : new Attribute(name, value));
                }
            }
            return list;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute.getName());
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            MBeanAttributeInfo[] attributes = {
                    new MBeanAttributeInfo("Size", "int", "", true, false, false),
                    new MBeanAttributeInfo("Raw", "java.lang.String", "", true, false, false),
                    new MBeanAttributeInfo("Null", "java.lang.String", "", true, false, false),
                    new MBeanAttributeInfo("Pairs", AttributeList.class.getName(), "", true, false, false) };
            MBeanOperationInfo[] operations = {
                    operation("reset"),
                    operation("put", "java.lang.String", "long"),
                    operation("put", "java.lang.String", "int") };
            return new MBeanInfo(Quirky.class.getName(), "", attributes, null, operations, null);
        }
    }

    private TestTarget() {
    }

    private static MBeanOperationInfo operation(String name, String... types) {
        MBeanParameterInfo[] signature = new MBeanParameterInfo[types.length];
        for (int i = 0; i < types.length; i++) {
            signature[i] = new MBeanParameterInfo("p" + i, types[i], "");
        }
        return new MBeanOperationInfo(name, "", signature, "void", MBeanOperationInfo.ACTION);
    }

    public static void main(String[] args) throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        mbs.registerMBean(new Cache(), new ObjectName("ajmxtest:type=Cache"));
        mbs.registerMBean(new Settings(), new ObjectName("ajmxtest:type=Settings"));
        mbs.registerMBean(new Quirky(), new ObjectName("ajmxtest:type=Quirky"));
        mbs.registerMBean(new ArrayEcho(), new ObjectName("ajmxtest:type=ArrayEcho"));
        mbs.registerMBean(new Evolving(), new ObjectName("ajmxtest:type=Evolving"));
        mbs.registerMBean(new Sleepy(), new ObjectName("ajmxtest:type=Sleepy"));
        String name = ManagementFactory.getRuntimeMXBean().getName();
        System.out.println("READY " + name.substring(0, name.indexOf('@')));
        System.out.flush();
        while (true) {
            Thread.sleep(60_000);
        }
    }
}
