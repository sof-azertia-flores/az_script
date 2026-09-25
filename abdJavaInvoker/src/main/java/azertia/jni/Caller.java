package azertia.jni;

import azertia.AbdInvoker;
import azertia.jfunc.JfuncExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Low-level JNI API using raw unsigned address bits in long. Prefer AbdInvoker. */
public final class Caller {
    private Caller() {}
    public static final List<Integer> namespaces = new CopyOnWriteArrayList<>();
    public static final Map<Integer, JfuncExecutor> executors = new ConcurrentHashMap<>();
    public static final Map<Integer, Function<Object[], Object>> returningExecutors = new ConcurrentHashMap<>();

    public static native void init();
    public static native void loadScript(String scriptPosition);
    public static native void insertScript(String scriptPosition);
    public static native void flush();
    public static native int namespaceForHint(String hint);
    public static native long call(int functionId, long[] args);

    /** Arguments belong to the native callback frame and must never be freed here. */
    public static Object callbackValue(int id, long[] args) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) values[i] = AbdInvoker.readValue(args[i]);
        Function<Object[], Object> returning = returningExecutors.get(id);
        if (returning != null) return returning.apply(values);
        JfuncExecutor executor = executors.get(id);
        if (executor == null) throw new IllegalArgumentException("Unknown Java function id: " + id);
        executor.run(values);
        return null;
    }
    /** Void-callback adapter; arguments use the same 64-bit slot addresses. */
    public static int callback(int id, long[] args) { callbackValue(id, args); return 0; }
    public static native int getMemType(long pointer);
    public static native String getMemStr(long pointer);
    public static native float getMemFloat(long pointer);
    public static native double getMemDouble(long pointer);
    public static native boolean getMemBool(long pointer);
    public static native int getMemInt(long pointer);
    /** Return the unsigned address bits held by an ADDRESS_VALUE slot. */
    public static native long getMemAddress(long pointer);
}
