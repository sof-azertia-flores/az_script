package azertia.binary;

import azertia.binary.complexBinary.*;
import azertia.binary.structures.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Dependency-free test runner, also used for cross-language fixture interchange. */
public final class AbdRegression {
    private interface Operation { void run() throws Exception; }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); }
        catch (IllegalArgumentException | IndexOutOfBoundsException ex) { return; }
        throw new AssertionError("malformed input accepted");
    }
    public static AcsObject fixture() {
        AcsObject result = new AcsObject();
        result.put("intMin", Integer.MIN_VALUE);
        result.put("intMax", Integer.MAX_VALUE);
        result.put("double", -1234.125);
        result.put("float", 1.25f);
        result.put("bool", true);
        result.put("text", "你好\0ABD");
        result.put("empty", "");
        result.put("bytes", new byte[]{0, 0, (byte)0xff, (byte)0x80});
        result.put("big", BigInteger.valueOf(-129));
        AcsArray array = new AcsArray();
        array.acsa.add(new AcsIntegerElement(-7));
        AcsObject child = new AcsObject();
        child.put("nested", false);
        array.acsa.add(child);
        result.put("array", array);
        result.put("negativeZero", -0.0);
        result.put("nan", Float.intBitsToFloat(0x7fc12345));
        result.put("addressZero", new AcsAddress(0L));
        result.put("addressEndian", new AcsAddress(0x0102030405060708L));
        result.put("addressMax", new AcsAddress("18446744073709551615"));
        return result;
    }
    private static void validate(AcsObject result) {
        check(result.mmp.size() == 15, "map cardinality");
        check(result.getAsInt("intMin") == Integer.MIN_VALUE, "minimum integer");
        check(result.getAsInt("intMax") == Integer.MAX_VALUE, "maximum integer");
        check(result.getAsDouble("double") == -1234.125, "double endian");
        check(result.getAsFloat("float") == 1.25f, "float endian");
        check(result.getAsBool("bool"), "boolean");
        check(result.getAsString("text").equals("你好\0ABD"), "UTF-8 and NUL");
        check(result.getAsString("empty").isEmpty(), "empty string");
        check(Arrays.equals(result.getAsBytes("bytes"), new byte[]{0, 0, (byte)0xff, (byte)0x80}), "byte[] leading zeroes");
        check(result.getAsBigInteger("big").equals(BigInteger.valueOf(-129)), "signed BigInteger");
        AcsArray array = result.getAsAcsArray("array");
        check(array.acsa.size() == 2 && ((AcsIntegerElement)array.acsa.get(0)).s == -7, "array");
        check(!((AcsObject)array.acsa.get(1)).getAsBool("nested"), "nested object");
        check(Double.doubleToRawLongBits(result.getAsDouble("negativeZero")) == Long.MIN_VALUE, "negative zero");
        check(Float.floatToRawIntBits(result.getAsFloat("nan")) == 0x7fc12345, "NaN payload");
        check(result.getAsAddress("addressZero").rawBits() == 0L, "zero address");
        check(result.getAsAddress("addressEndian").rawBits() == 0x0102030405060708L, "address endian");
        check(result.getAsAddress("addressMax").rawBits() == -1L, "unsigned maximum address bits");
        check(result.getAsAddress("addressMax").toUnsignedString().equals("18446744073709551615"), "unsigned address string");
        check(result.getAsAddress("addressMax").toBigInteger().equals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)), "unsigned address BigInteger");
    }
    @AsStructure public static class Record {
        @AsColum(order = 3) public String text;
        @AsColum public int integer;
        @AsColum public BigInteger big;
        @AsColum public float f;
        @AsColum public double d;
        @AsColum public long l;
        @AsColum public short s;
        @AsColum public char c;
        @AsColum public boolean b;
        @AsColum @AsList(key = String.class) public List<String> list;
        @AsColum @AsMap(key = String.class, value = Integer.class) public Map<String, Integer> map;
        @AsColum public Integer nullable;
        @AsColum public byte[] bytes;
    }
    @AsStructure public static class BadOrder {
        @AsColum(order = 2) public int value;
    }
    @AsStructure public static class DuplicateOrder {
        @AsColum(order = 1) public int one;
        @AsColum(order = 1) public int two;
    }
    private static void structures() throws Exception {
        Record original = new Record();
        original.text = "";
        original.integer = Integer.MIN_VALUE;
        original.big = BigInteger.ONE.shiftLeft(128).negate();
        original.f = -3.25f;
        original.d = 1e100;
        original.l = Long.MIN_VALUE;
        original.s = Short.MIN_VALUE;
        original.c = '猫';
        original.b = true;
        original.list = new ArrayList<>(List.of("", "value"));
        original.map = new LinkedHashMap<>();
        original.map.put("key", 42);
        original.bytes = new byte[]{0, 0, (byte)0xff};
        AbdValue bytes = AsStructIO.getAbdStructure(original, false);
        Record restored = (Record)AsStructIO.readAbdStructure(bytes, Record.class, false);
        check(restored.text.isEmpty() && restored.integer == original.integer, "ordered fields or empty string");
        check(restored.big.equals(original.big), "reflection BigInteger");
        check(restored.f == original.f && restored.d == original.d, "reflection floating point");
        check(restored.l == original.l && restored.s == original.s && restored.c == original.c && restored.b, "reflection scalars");
        check(restored.list.equals(original.list) && restored.map.equals(original.map), "interface collections");
        check(restored.nullable == null && Arrays.equals(restored.bytes, original.bytes), "nullable/byte array fields");
        check(Arrays.equals(AsStructIO.getAbdStructure(restored, false).toAbdFormat(), bytes.toAbdFormat()), "structure roundtrip");
        check(((AcsStringElement)AsStructIO.readAbdStructure(new AbdValue(), AcsStringElement.class, false)).s.isEmpty(), "empty ACS string reflection");
        check(((AcsArray)AsStructIO.readAbdStructure(new AbdValue(), AcsArray.class, false)).acsa.isEmpty(), "empty ACS array reflection");
        rejected(() -> AsStructIO.getAbdStructure(new BadOrder(), false));
        rejected(() -> AsStructIO.getAbdStructure(new DuplicateOrder(), false));
        var truncated = bytes.getAsAss(); truncated.values.remove(0);
        rejected(() -> AsStructIO.readAbdStructure(truncated.toAbd(), Record.class, false));
        var serializable = new ArrayList<>(List.of("hello"));
        check(AsStructIO.readAbdStructure(AsStructIO.getAbdStructure(serializable, true), ArrayList.class, true).equals(serializable), "explicit Serializable compatibility");
        rejected(() -> AsStructIO.readAbdStructure(AsStructIO.getAbdStructure(serializable, true), HashMap.class, true));
        AcsWrappedObject<Record> wrapped = new AcsWrappedObject<>(original);
        check(((Record)new AcsWrappedObject<>(wrapped.toValue()).get()).map.equals(original.map), "wrapped annotated structure");
        AcsWrappedObject<Object> wrappedNull = new AcsWrappedObject<>((Object)null);
        check(new AcsWrappedObject<>(wrappedNull.toValue()).get() == null, "wrapped null");
    }
    private static void runTests() throws Exception {
        AcsObject source = fixture();
        byte[] frame = source.toValue().toAbdFormat();
        AcsObject restored = new AcsObject(AbdValue.fromAbd(frame));
        validate(restored);
        check(Arrays.equals(restored.toValue().toAbdFormat(), frame), "byte-exact roundtrip");
        source.put("intMin", 12);
        check(source.mmp.size() == 15 && source.getAsInt("intMin") == 12, "map replacement");
        for (int size = 0; size < frame.length; size++) {
            byte[] truncated = Arrays.copyOf(frame, size);
            rejected(() -> AbdValue.fromAbd(truncated));
        }
        rejected(() -> AbdValue.fromAbd(Arrays.copyOf(frame, frame.length + 1)));
        rejected(() -> AbdValue.fromAbd(new byte[]{-1, -1, -1, -1}));
        rejected(() -> AbdValue.fromAbd(new byte[]{-1, -1, -1, 0x7f}));
        rejected(() -> new AcsDouble(new AbdValue(new byte[7])));
        rejected(() -> new AcsFloat(new AbdValue(new byte[3])));
        rejected(() -> new AcsIntegerElement(new AbdValue(new byte[5])));
        rejected(() -> new AcsBooleanElement(new AbdValue(new byte[]{2})));
        check(Arrays.equals(new AcsAddress(0x0102030405060708L).toValue().getData(), new byte[]{8, 7, 6, 5, 4, 3, 2, 1}), "exact address bytes");
        check(new AcsAddress(-1L).equals(new AcsAddress(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))), "raw bits and checked integer address agree");
        check(new AcsAddress(-1L).toJson().toString().equals("{\"address\":\"18446744073709551615\"}"), "address JSON is unsigned and typed");
        for (int size = 0; size <= 9; size++) {
            if (size == 8) continue;
            AbdValue payload = new AbdValue(new byte[size]);
            rejected(() -> new AcsAddress(payload));
            AbdSimpleStack typedPayload = new AbdSimpleStack();
            typedPayload.values.add(AbdBasicType.int2Abd(AcsAddress.TYPE));
            typedPayload.values.add(payload);
            rejected(() -> AcsArray.aoa(typedPayload.toAbd()));
        }
        for (String bad : List.of("", "-1", "+1", "1.0", "0x1", "18446744073709551616"))
            rejected(() -> new AcsAddress(bad));
        rejected(() -> new AcsAddress(BigInteger.valueOf(-1)));
        rejected(() -> new AcsAddress(BigInteger.ONE.shiftLeft(64)));
        byte[] backing = {1, 2};
        AbdValue owned = new AbdValue(backing); backing[0] = 9;
        owned.getData()[0] = 5;
        check(owned.getData()[0] == 1, "payload owns its bytes");
        AcsObject binary = new AcsObject();
        binary.put("empty", new byte[0]);
        binary.put("leadingZero", new byte[]{0, 0, 1});
        binary = new AcsObject(binary.toValue());
        check(binary.getAsBytes("empty").length == 0, "empty byte array");
        check(Arrays.equals(binary.getAsBytes("leadingZero"), new byte[]{0, 0, 1}), "byte leading zeroes");
        AcsBigInteger legacy = new AcsBigInteger(new AbdValue(new byte[]{0, 0, 1}));
        check(Arrays.equals(legacy.toValue().getData(), new byte[]{0, 0, 1}), "legacy raw bytes");
        legacy.value = BigInteger.TEN;
        check(Arrays.equals(legacy.toValue().getData(), new byte[]{10}), "changed BigInteger serialized");
        AbdSimpleStack badArray = new AbdSimpleStack();
        badArray.values.add(AbdBasicType.int2Abd(-1));
        rejected(() -> AcsArray.aoa(badArray.toAbd()));
        badArray.values.add(new AbdValue());
        rejected(() -> AcsArray.aoa(badArray.toAbd()));
        AbdSimpleStack duplicates = new AbdSimpleStack();
        for (int n = 0; n < 2; n++) {
            duplicates.values.add(AbdBasicType.string2Abd("same"));
            duplicates.values.add(AbdBasicType.int2Abd(3));
            duplicates.values.add(AbdBasicType.int2Abd(n));
        }
        rejected(() -> new AcsObject(duplicates.toAbd()));
        AcsArray cyclic = new AcsArray(); cyclic.acsa.add(cyclic);
        rejected(cyclic::toValue); rejected(cyclic::toJson);
        AbdValue nested = new AbdValue();
        for (int n = 0; n < 140; n++) {
            AbdSimpleStack stack = new AbdSimpleStack();
            stack.values.add(AbdBasicType.int2Abd(0xad));
            stack.values.add(nested);
            nested = stack.toAbd();
        }
        AbdValue excessivelyNested = nested;
        rejected(() -> AcsArray.aoa(excessivelyNested));
        structures();
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--write")) {
            Files.write(Path.of(args[1]), fixture().toValue().toAbdFormat());
        } else if (args.length == 2 && args[0].equals("--read")) {
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            AcsObject value = new AcsObject(AbdValue.fromAbd(bytes));
            validate(value);
            check(Arrays.equals(bytes, value.toValue().toAbdFormat()), "cross-language roundtrip differs");
        } else runTests();
        System.out.println("ABD Java regression passed");
    }
}
