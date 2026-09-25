package azertia.binary.structures;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.AcsElement;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Annotation-based Java structures. Historical scalar encoding is retained:
 * int is little-endian; other multibyte Java primitives are big-endian.
 * Prefer typed AcsObject for portable C++ interchange.
 */
public final class AsStructIO {
    private AsStructIO() {}
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final class Guard implements AutoCloseable {
        Guard() {
            int depth = DEPTH.get();
            if (depth >= 128) throw new IllegalArgumentException("structure nesting exceeds 128 levels or contains a cycle");
            DEPTH.set(depth + 1);
        }
        @Override public void close() {
            int depth = DEPTH.get() - 1;
            if (depth == 0) DEPTH.remove(); else DEPTH.set(depth);
        }
    }
    private static List<Field> columns(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        collectColumns(type, fields);
        Field[] ordered = new Field[fields.size()];
        for (Field field : fields) {
            int order = field.getAnnotation(AsColum.class).order();
            if (order < 0 || order > ordered.length) throw new IllegalArgumentException("column order out of range: " + field);
            if (order != 0) {
                if (ordered[order - 1] != null) throw new IllegalArgumentException("duplicate column order: " + order);
                ordered[order - 1] = field;
            }
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                throw new IllegalArgumentException("annotated field must be an instance, writable field: " + field);
            field.setAccessible(true);
        }
        int index = 0;
        for (Field field : fields) {
            if (field.getAnnotation(AsColum.class).order() != 0) continue;
            while (ordered[index] != null) index++;
            ordered[index] = field;
        }
        return Arrays.asList(ordered);
    }
    private static void collectColumns(Class<?> type, List<Field> fields) {
        if (type.getSuperclass() != null) collectColumns(type.getSuperclass(), fields);
        for (Field field : type.getDeclaredFields())
            if (field.isAnnotationPresent(AsColum.class)) fields.add(field);
    }
    public static AbdValue getAbdStructure(Object obj, boolean compatMode) {
        try (Guard ignored = new Guard()) {
            if (obj == null) return new AbdValue();
            if (obj instanceof AcsElement) return ((AcsElement)obj).toValue();
            Class<?> type = obj.getClass();
            if (type.isAnnotationPresent(AsStructure.class)) {
                Method serializer = null;
                for (Method method : type.getMethods()) {
                    if (!method.isAnnotationPresent(AsSerializer.class)) continue;
                    if (serializer != null || method.getReturnType() != AbdValue.class || method.getParameterCount() != 0)
                        throw new IllegalArgumentException("structure must have one no-argument ABD serializer");
                    serializer = method;
                }
                if (serializer != null) {
                    try { return Objects.requireNonNull((AbdValue)serializer.invoke(obj), "serializer result"); }
                    catch (ReflectiveOperationException ex) { throw reflectionError(ex); }
                }
                AbdSimpleStack result = new AbdSimpleStack();
                for (Field field : columns(type)) {
                    try {
                        Object value = field.get(obj);
                        if (value == null) result.values.add(new AbdValue());
                        else if (Map.class.isAssignableFrom(field.getType()) && field.isAnnotationPresent(AsMap.class)) {
                            AbdSimpleStack entries = new AbdSimpleStack();
                            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) {
                                entries.values.add(getAbdStructure(entry.getKey(), compatMode));
                                entries.values.add(getAbdStructure(entry.getValue(), compatMode));
                            }
                            result.values.add(entries.toAbd());
                        } else if (List.class.isAssignableFrom(field.getType()) && field.isAnnotationPresent(AsList.class)) {
                            AbdSimpleStack entries = new AbdSimpleStack();
                            for (Object entry : (List<?>)value) entries.values.add(getAbdStructure(entry, compatMode));
                            result.values.add(entries.toAbd());
                        } else {
                            if (field.getType() == Number.class && !(value instanceof Integer))
                                throw new IllegalArgumentException("legacy Number fields only preserve Integer; use a concrete numeric field type");
                            result.values.add(getAbdStructure(value, compatMode));
                        }
                    } catch (IllegalAccessException ex) { throw reflectionError(ex); }
                }
                return result.toAbd();
            }
            if (obj instanceof String) return AbdBasicType.string2Abd((String)obj);
            if (obj instanceof byte[]) return new AbdValue((byte[])obj);
            if (obj instanceof Boolean) return AbdBasicType.bol2Abd((Boolean)obj);
            if (obj instanceof Integer) return AbdBasicType.int2Abd((Integer)obj);
            if (obj instanceof BigInteger) return new AbdValue(((BigInteger)obj).toByteArray());
            if (obj instanceof Double) return new AbdValue(ByteBuffer.allocate(8).putDouble((Double)obj).array());
            if (obj instanceof Float) return new AbdValue(ByteBuffer.allocate(4).putFloat((Float)obj).array());
            if (obj instanceof Long) return new AbdValue(ByteBuffer.allocate(8).putLong((Long)obj).array());
            if (obj instanceof Short) return new AbdValue(ByteBuffer.allocate(2).putShort((Short)obj).array());
            if (obj instanceof Character) return new AbdValue(ByteBuffer.allocate(2).putChar((Character)obj).array());
            if (obj instanceof Byte) return new AbdValue(new byte[]{(Byte)obj});
            if (compatMode && obj instanceof Serializable) {
                try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                    out.writeObject(obj);
                    out.flush();
                    return new AbdValue(buffer.toByteArray());
                } catch (IOException ex) { throw new IllegalArgumentException("cannot serialize Java object", ex); }
            }
            throw new IllegalArgumentException("unsupported structure type: " + type.getName());
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map readMap(AbdValue value, Class mapType, Class key, Class elementType, boolean compatMode) throws NoSuchMethodException {
        try (Guard ignored = new Guard()) {
            var entries = value.getAsAss().values;
            if (entries.size() % 2 != 0) throw new IllegalArgumentException("map requires key/value pairs");
            Map result;
            if (mapType == Map.class) result = new LinkedHashMap();
            else if (mapType == SortedMap.class || mapType == NavigableMap.class) result = new TreeMap();
            else result = (Map)newInstance(mapType);
            for (int i = 0; i < entries.size(); i += 2)
                result.put(readAbdStructure(entries.get(i), key, compatMode), readAbdStructure(entries.get(i + 1), elementType, compatMode));
            return result;
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List readList(AbdValue value, Class listType, Class elementType, boolean compatMode) {
        try (Guard ignored = new Guard()) {
            List result = listType == List.class ? new ArrayList() : (List)newInstance(listType);
            for (AbdValue entry : value.getAsAss().values) result.add(readAbdStructure(entry, elementType, compatMode));
            return result;
        }
    }
    private static Object newInstance(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) { throw reflectionError(ex); }
    }
    public static Object readAbdStructure(AbdValue value, Class<?> type, boolean compatMode) {
        Objects.requireNonNull(value, "structure payload");
        Objects.requireNonNull(type, "structure type");
        try (Guard ignored = new Guard()) {
            byte[] bytes = value.getData();
            // The old format represents null and empty data alike. For strings
            // and bytes, preserving a legitimate empty value takes precedence.
            if (type == String.class) return new String(bytes, StandardCharsets.UTF_8);
            if (type == byte[].class) return bytes;
            if (AcsElement.class.isAssignableFrom(type) || type.isAnnotationPresent(AsConstructor.class)) {
                try {
                    Constructor<?> constructor = type.getDeclaredConstructor(AbdValue.class);
                    constructor.setAccessible(true);
                    return constructor.newInstance(value);
                } catch (ReflectiveOperationException ex) { throw reflectionError(ex); }
            }
            if (bytes.length == 0 && !type.isPrimitive()) {
                if (!type.isAnnotationPresent(AsStructure.class) || !columns(type).isEmpty()) return null;
            }
            if (type.isAnnotationPresent(AsStructure.class)) {
                var entries = value.getAsAss().values;
                var fields = columns(type);
                if (entries.size() != fields.size()) throw new IllegalArgumentException("structure column count mismatch");
                Object result = newInstance(type);
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    try {
                        if (Map.class.isAssignableFrom(field.getType()) && field.isAnnotationPresent(AsMap.class)) {
                            AsMap annotation = field.getAnnotation(AsMap.class);
                            field.set(result, readMap(entries.get(i), field.getType(), annotation.key(), annotation.value(), compatMode));
                        } else if (List.class.isAssignableFrom(field.getType()) && field.isAnnotationPresent(AsList.class)) {
                            field.set(result, readList(entries.get(i), field.getType(), field.getAnnotation(AsList.class).key(), compatMode));
                        } else field.set(result, readAbdStructure(entries.get(i), field.getType(), compatMode));
                    } catch (ReflectiveOperationException ex) { throw reflectionError(ex); }
                }
                return result;
            }
            if (type == int.class || type == Integer.class) return AbdBasicType.abd2int(value);
            if (type == boolean.class || type == Boolean.class) return AbdBasicType.abd2bol(value);
            if (type == BigInteger.class) return new BigInteger(bytes);
            // Legacy Number fields were written according to the runtime type.
            // Only a 4-byte integer can be recovered without extra type metadata.
            if (type == Number.class && bytes.length == 4) return AbdBasicType.abd2int(value);
            if (type == double.class || type == Double.class) { AbdBasicType.requireSize(value, 8); return ByteBuffer.wrap(bytes).getDouble(); }
            if (type == long.class || type == Long.class) { AbdBasicType.requireSize(value, 8); return ByteBuffer.wrap(bytes).getLong(); }
            if (type == float.class || type == Float.class) { AbdBasicType.requireSize(value, 4); return ByteBuffer.wrap(bytes).getFloat(); }
            if (type == short.class || type == Short.class) { AbdBasicType.requireSize(value, 2); return ByteBuffer.wrap(bytes).getShort(); }
            if (type == char.class || type == Character.class) { AbdBasicType.requireSize(value, 2); return ByteBuffer.wrap(bytes).getChar(); }
            if (type == byte.class || type == Byte.class) { AbdBasicType.requireSize(value, 1); return bytes[0]; }
            if (compatMode && Serializable.class.isAssignableFrom(type)) {
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                    // Compatibility mode is for trusted application objects;
                    // retain caller/JVM filters and impose resource limits too.
                    ObjectInputFilter configured = ObjectInputFilter.Config.getSerialFilter();
                    in.setObjectInputFilter(info -> {
                        if (info.depth() > 128 || info.references() > 100000 || info.arrayLength() > 10000000)
                            return ObjectInputFilter.Status.REJECTED;
                        return configured == null ? ObjectInputFilter.Status.UNDECIDED : configured.checkInput(info);
                    });
                    Object result = in.readObject();
                    if (!type.isInstance(result)) throw new IllegalArgumentException("serialized Java object has wrong type");
                    return result;
                } catch (IOException | ClassNotFoundException ex) { throw new IllegalArgumentException("cannot deserialize Java object", ex); }
            }
            throw new IllegalArgumentException("unsupported structure type: " + type.getName());
        }
    }
    private static IllegalArgumentException reflectionError(ReflectiveOperationException ex) {
        Throwable cause = ex instanceof InvocationTargetException ? ((InvocationTargetException)ex).getTargetException() : ex;
        return new IllegalArgumentException("invalid annotated ABD structure", cause);
    }
}
