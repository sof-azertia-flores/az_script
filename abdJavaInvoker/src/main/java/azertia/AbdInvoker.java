package azertia;

import azertia.jfunc.JfuncExecutor;
import azertia.jni.Caller;
import azertia.jni.Caller20;
import azertia.jni.Caller220;
import azertia.jni.Caller2220;
import azertia.jni.Caller22220;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.Function;

/**
 * Process-wide embedded runtime. Calls are serialized and callbacks may reenter
 * invoke on the same thread. A callback must not wait for another thread that
 * calls this runtime. Use one shared native library per JVM.
 */
public final class AbdInvoker {
    private AbdInvoker() {}
    public static final int INT_VALUE = 0;
    public static final int STRING_VALUE = 1;
    public static final int FLOAT_VALUE = 2;
    public static final int DOUBLE_VALUE = 3;
    public static final int BOOLEAN_VALUE = 4;
    public static final int VOID_VALUE = 5;
    public static final int ADDRESS_VALUE = 7;
    /** A literal object value; it never crosses the Java boundary. */
    public static final int OBJECT_VALUE = 8;
    private static boolean initialized;
    private static boolean loaded;
    private static int activeCalls;

    private static void tryInit() {
        if (!initialized) {
            // Set the flag after successful initialization so a failed load can retry.
            String absoluteLibrary = System.getProperty("azertia.native.library");
            if (absoluteLibrary == null) System.loadLibrary("abdJ");
            else System.load(new File(absoluteLibrary).getAbsolutePath());
            Caller.init();
            initialized = true;
        }
    }
    private static void requireLoaded() {
        if (!loaded) throw new IllegalStateException("No script is loaded");
    }
    public static synchronized void loadScript(File scriptFile) {
        Objects.requireNonNull(scriptFile, "scriptFile");
        tryInit();
        if (loaded) throw new IllegalStateException("A script is already loaded");
        // Loading only mounts the initial module. flush performs linking and
        // initialization after all of its libraries have been inserted.
        activeCalls++;
        loaded = true;
        try {
            Caller.loadScript(scriptFile.getAbsolutePath());
        } catch (RuntimeException | Error e) {
            loaded = false;
            throw e;
        } finally { activeCalls--; }
    }
    /** Mount another module; call flush before invoking or taking a snapshot. */
    public static synchronized void insertScript(File scriptFile) {
        Objects.requireNonNull(scriptFile, "scriptFile");
        tryInit(); requireLoaded();
        if (activeCalls != 0) throw new IllegalStateException("Functions are running");
        activeCalls++;
        try { Caller.insertScript(scriptFile.getAbsolutePath()); }
        finally { activeCalls--; }
    }
    /** Resolve library hints, then initialize each newly mounted module once. */
    public static synchronized void flush() {
        tryInit(); requireLoaded();
        if (activeCalls != 0) throw new IllegalStateException("Functions are running");
        activeCalls++;
        try { Caller.flush(); }
        finally { activeCalls--; }
    }
    /** Return a mounted library's actual namespace (unsigned 16-bit value). */
    public static synchronized int namespaceForHint(String hint) {
        Objects.requireNonNull(hint, "hint");
        tryInit(); requireLoaded();
        return Caller.namespaceForHint(hint);
    }
    public static synchronized Object invoke(int functionId, Object... args) {
        Objects.requireNonNull(args, "args");
        tryInit(); requireLoaded();
        for (Object arg : args) checkValue(arg);
        activeCalls++;
        long block = 0;
        long result = 0;
        try {
            block = args.length == 0 ? 0 : Caller20.memAlloc(args.length);
            long[] pointers = new long[args.length];
            for (int i = 0; i < args.length; i++) {
                pointers[i] = block + i;
                putValue(pointers[i], args[i]);
            }
            result = Caller.call(functionId, pointers);
            return result == 0 ? null : readValue(result);
        } finally {
            // A Java callback or a native error can throw at any point. Every
            // allocated block must still be released and the call count restored.
            try { if (result != 0) Caller20.memFree(result); }
            finally {
                try { if (block != 0) Caller20.memFree(block); }
                finally { activeCalls--; }
            }
        }
    }
    private static void checkValue(Object value) {
        if (value != null && !(value instanceof Integer) && !(value instanceof Float)
                && !(value instanceof Double) && !(value instanceof Boolean) && !(value instanceof String)
                && !(value instanceof Address)) {
            throw new IllegalArgumentException("Unsupported argument type: " + value.getClass().getName());
        }
    }
    private static void putValue(long pointer, Object value) {
        if (value == null) Caller220.ACputMemNull(pointer);
        else if (value instanceof Integer) Caller220.ACputMem(pointer, (Integer) value);
        else if (value instanceof Float) Caller220.ACputMem(pointer, (Float) value);
        else if (value instanceof Double) Caller220.ACputMem(pointer, (Double) value);
        else if (value instanceof Boolean) Caller220.ACputMem(pointer, (Boolean) value);
        else if (value instanceof Address) Caller220.ACputMemAddress(pointer, ((Address) value).bits());
        else Caller220.ACputMem(pointer, (String) value);
    }
    /** Read a caller-owned native slot without releasing it. */
    public static Object readValue(long pointer) {
        switch (Caller.getMemType(pointer)) {
            case INT_VALUE: return Caller.getMemInt(pointer);
            case FLOAT_VALUE: return Caller.getMemFloat(pointer);
            case DOUBLE_VALUE: return Caller.getMemDouble(pointer);
            case BOOLEAN_VALUE: return Caller.getMemBool(pointer);
            case STRING_VALUE: return Caller.getMemStr(pointer);
            case ADDRESS_VALUE: return Address.of(Caller.getMemAddress(pointer));
            case VOID_VALUE: return null;
            case OBJECT_VALUE: throw new IllegalStateException("A literal object cannot be read from Java; read its fields through a pointer");
            default: throw new IllegalStateException("Unknown native value type");
        }
    }
    /** Register a legacy void callback; it returns null to the script. */
    public static synchronized void registerJfunction(JfuncExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        tryInit();
        bind(executor.getId());
        Caller.returningExecutors.remove(executor.getId());
        Caller.executors.put(executor.getId(), executor);
    }
    /** Register a callback returning Integer, Float, Double, Boolean, String, Address or null. */
    public static synchronized void registerJfunction(int functionId, Function<Object[], Object> executor) {
        Objects.requireNonNull(executor, "executor");
        tryInit();
        bind(functionId);
        Caller.executors.remove(functionId);
        Caller.returningExecutors.put(functionId, executor);
    }
    private static void bind(int id) {
        int namespace = id >>> 16;
        if (!Caller.namespaces.contains(namespace)) {
            Caller2220.bindNamespace(namespace);
            Caller.namespaces.add(namespace);
        }
    }
    /** Trust one or more PEM SubjectPublicKeyInfo blocks for load_extern_library. */
    public static synchronized void addTrustedPublicKey(String pem) {
        Objects.requireNonNull(pem, "pem");
        tryInit();
        Caller2220.addTrustedPublicKey(pem);
    }
    /** Read a PEM public key file and trust it for load_extern_library. */
    public static synchronized void addTrustedPublicKey(File pemFile) throws IOException {
        Objects.requireNonNull(pemFile, "pemFile");
        addTrustedPublicKey(Files.readString(pemFile.toPath()));
    }
    public static synchronized void clearTrustedPublicKeys() {
        tryInit();
        Caller2220.clearTrustedPublicKeys();
    }
    public static synchronized void unregisterJfunction(int functionId) {
        Caller.executors.remove(functionId);
        Caller.returningExecutors.remove(functionId);
        int namespace = functionId >>> 16;
        boolean inUse = Caller.executors.keySet().stream().anyMatch(id -> (id >>> 16) == namespace)
                || Caller.returningExecutors.keySet().stream().anyMatch(id -> (id >>> 16) == namespace);
        if (!inUse && Caller.namespaces.contains(namespace)) {
            Caller2220.unbindNamespace(namespace);
            Caller.namespaces.remove(Integer.valueOf(namespace));
        }
    }
    public static synchronized boolean saveStatus(File statusFile) {
        Objects.requireNonNull(statusFile, "statusFile");
        tryInit(); requireLoaded();
        if (activeCalls != 0) return false;
        Caller22220.saveStatusToFile(statusFile.getAbsolutePath());
        return true;
    }
    public static synchronized void loadStatus(File statusFile) {
        Objects.requireNonNull(statusFile, "statusFile");
        tryInit(); requireLoaded();
        if (activeCalls != 0) throw new IllegalStateException("Functions are running");
        Caller22220.loadStatusFromFile(statusFile.getAbsolutePath());
    }
    /** Idempotently destroy the script; registrations remain available for reload. */
    public static synchronized void destroyScript() {
        if (!initialized) return;
        if (activeCalls != 0) throw new IllegalStateException("Functions are running");
        try { Caller20.destroyScript(); }
        finally { loaded = false; }
    }
    /** Release script state and callback global references before unloading a host. */
    public static synchronized void close() {
        if (activeCalls != 0) throw new IllegalStateException("Functions are running");
        try { destroyScript(); }
        finally {
            for (int namespace : Caller.namespaces) Caller2220.unbindNamespace(namespace);
            Caller.namespaces.clear();
            Caller.executors.clear();
            Caller.returningExecutors.clear();
        }
    }
}
