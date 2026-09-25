package azertia;

import azertia.jfunc.JfuncExecutor;
import azertia.jni.Caller;
import azertia.jni.Caller20;
import azertia.jni.Caller220;
import azertia.jni.Caller22220;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests run against the actual shared library under -Xcheck:jni, without mocks. */
public final class JniRegression {
    private static int checks;
    private static void equal(Object wanted, Object actual) {
        checks++;
        if (!Objects.equals(wanted, actual)) throw new AssertionError("Expected " + wanted + ", got " + actual);
    }
    private static void fails(Class<? extends Throwable> kind, Runnable operation) {
        checks++;
        try { operation.run(); }
        catch (Throwable error) {
            if (kind.isInstance(error)) return;
            throw new AssertionError("Expected " + kind.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + kind.getName());
    }
    private static void loadAndFlush(File script) {
        AbdInvoker.loadScript(script);
        AbdInvoker.flush();
    }
    private static void objectSnapshots(File work) {
        List<Integer> destructed = new ArrayList<>();
        AbdInvoker.registerJfunction(0x34560004, values -> { destructed.add((Integer) values[0]); return null; });
        File script = new File(work, "objects.exec.abd");
        File saved = new File(work, "objects.saved.snapshot.abd");
        long hostAllocation = Caller20.memAlloc(1);
        Caller220.ACputMem(hostAllocation, 1234);
        AbdInvoker.loadScript(new File(work, "objects.load-failure.exec.abd"));
        fails(IllegalStateException.class, AbdInvoker::flush);
        fails(IllegalStateException.class, () -> AbdInvoker.invoke(207));
        fails(IllegalStateException.class, () -> AbdInvoker.saveStatus(saved));
        fails(IllegalStateException.class, () -> AbdInvoker.loadStatus(saved));
        fails(IllegalStateException.class, AbdInvoker::flush);
        fails(IllegalStateException.class, () -> AbdInvoker.insertScript(script));
        equal(1234, Caller.getMemInt(hostAllocation));
        equal(List.of(), destructed);
        AbdInvoker.destroyScript();
        equal(1234, Caller.getMemInt(hostAllocation));
        loadAndFlush(script);
        equal(true, Caller22220.globalMemories2Str().endsWith("Allocations: 1\n"));
        equal(true, AbdInvoker.saveStatus(new File(work, "objects.after-failed-load.snapshot.abd")));
        Caller20.memFree(hostAllocation);
        Address first = (Address) AbdInvoker.invoke(201, 11);
        Address second = (Address) AbdInvoker.invoke(201, 22);
        Address manual = (Address) AbdInvoker.invoke(202, 33);
        equal(true, AbdInvoker.saveStatus(saved));
        Caller220.ACputMem(first.bits(), 71);
        Address extra = (Address) AbdInvoker.invoke(201, 44);
        AbdInvoker.invoke(202, 55);
        AbdInvoker.invoke(206, 99);
        AbdInvoker.loadStatus(saved);
        equal(List.of(), destructed); // State replacement never calls old user destructors.
        equal(7, AbdInvoker.invoke(207));
        equal(11, AbdInvoker.invoke(204, first));
        equal(22, AbdInvoker.invoke(204, second));
        equal(33, AbdInvoker.invoke(204, manual));
        fails(RuntimeException.class, () -> AbdInvoker.invoke(204, extra));

        File[] invalidSnapshots = work.listFiles((directory, name) -> name.startsWith("objects.invalid-") && name.endsWith(".snapshot.abd"));
        if (invalidSnapshots == null || invalidSnapshots.length < 15) throw new AssertionError("Missing malformed object fixtures");
        for (File invalid : invalidSnapshots) {
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(invalid));
            equal(7, AbdInvoker.invoke(207));
            equal(11, AbdInvoker.invoke(204, first));
            equal(22, AbdInvoker.invoke(204, second));
            equal(33, AbdInvoker.invoke(204, manual));
            equal(List.of(), destructed);
        }
        fails(RuntimeException.class, () -> AbdInvoker.invoke(203, first));
        AbdInvoker.invoke(203, manual);
        equal(List.of(33), destructed);
        fails(RuntimeException.class, () -> AbdInvoker.invoke(203, manual));
        AbdInvoker.loadStatus(saved);
        equal(List.of(33), destructed);
        AbdInvoker.invoke(203, manual);
        AbdInvoker.destroyScript();
        equal(List.of(33, 33, 22, 11), destructed);

        // Ownership order is semantically significant and need not match addresses.
        destructed.clear();
        loadAndFlush(script);
        AbdInvoker.loadStatus(new File(work, "objects.valid.snapshot.abd"));
        equal(66, AbdInvoker.invoke(207));
        equal(11, AbdInvoker.invoke(204, Address.of(1)));
        equal(22, AbdInvoker.invoke(204, Address.of(2)));
        Caller20.memFree(3); // Raw Java release must remove registration without invoking user code.
        fails(RuntimeException.class, () -> AbdInvoker.invoke(203, Address.of(3)));
        equal(List.of(), destructed);
        AbdInvoker.loadStatus(new File(work, "objects.valid.snapshot.abd"));
        AbdInvoker.invoke(203, Address.of(3));
        AbdInvoker.destroyScript();
        equal(List.of(33, 11, 22), destructed);

        destructed.clear();
        loadAndFlush(script);
        AbdInvoker.invoke(201, 77);
        AbdInvoker.invoke(202, 88);
        AbdInvoker.loadStatus(new File(work, "objects.empty.snapshot.abd"));
        equal(67, AbdInvoker.invoke(207));
        AbdInvoker.destroyScript();
        equal(List.of(), destructed);

        loadAndFlush(script);
        AbdInvoker.loadStatus(new File(work, "objects.no-destructor.snapshot.abd"));
        AbdInvoker.invoke(203, Address.of(3));
        equal(List.of(), destructed);
        AbdInvoker.destroyScript();
        equal(List.of(11, 22), destructed);

        // make_free may detach an automatic object without changing how it was
        // created: snapshot that state, keep rejecting delete, and use raw free.
        destructed.clear();
        loadAndFlush(script);
        Address detached = (Address) AbdInvoker.invoke(208, 77);
        File detachedSnapshot = new File(work, "objects.detached.snapshot.abd");
        equal(true, AbdInvoker.saveStatus(detachedSnapshot));
        Caller20.memFree(detached.bits());
        AbdInvoker.loadStatus(detachedSnapshot);
        equal(77, AbdInvoker.invoke(204, detached));
        fails(RuntimeException.class, () -> AbdInvoker.invoke(203, detached));
        equal(List.of(), destructed);
        Caller20.memFree(detached.bits());
        AbdInvoker.destroyScript();
        equal(List.of(), destructed);

        UnsupportedOperationException destructorFailure = new UnsupportedOperationException("second object's destructor");
        IllegalArgumentException laterFailure = new IllegalArgumentException("first object's destructor");
        UnsupportedOperationException bodyFailure = new UnsupportedOperationException("original body callback");
        AbdInvoker.registerJfunction(0x34560004, values -> {
            int value = (Integer) values[0];
            destructed.add(value);
            throw value == 2 ? destructorFailure : laterFailure;
        });
        AbdInvoker.registerJfunction(0x34560005, values -> { throw bodyFailure; });
        loadAndFlush(script);
        try { AbdInvoker.invoke(213); throw new AssertionError("Expected destructor callback failure"); }
        catch (UnsupportedOperationException error) { equal(true, error == destructorFailure); }
        equal(List.of(2, 1), destructed);
        equal(true, Caller22220.globalMemories2Str().endsWith("Allocations: 0\n"));
        destructed.clear();
        try { AbdInvoker.invoke(214); throw new AssertionError("Expected original body callback failure"); }
        catch (UnsupportedOperationException error) { equal(true, error == bodyFailure); }
        equal(List.of(2, 1), destructed);
        equal(true, Caller22220.globalMemories2Str().endsWith("Allocations: 0\n"));
        destructed.clear();
        try { AbdInvoker.invoke(215); throw new AssertionError("Expected original native body failure"); }
        catch (IllegalStateException error) { equal("Division by zero", error.getMessage()); }
        equal(List.of(2, 1), destructed);
        equal(true, Caller22220.globalMemories2Str().endsWith("Allocations: 0\n"));
        AbdInvoker.destroyScript();
        AbdInvoker.unregisterJfunction(0x34560004);
        AbdInvoker.unregisterJfunction(0x34560005);
    }
    private static void numericSnapshots(File work) {
        List<Integer> destructed = new ArrayList<>();
        AbdInvoker.registerJfunction(0x34560004, values -> { destructed.add((Integer) values[0]); return null; });
        File saved = new File(work, "numeric.saved.snapshot.abd");
        loadAndFlush(new File(work, "numeric.exec.abd"));
        equal(7, AbdInvoker.invoke(207));
        equal("numeric\0中文🐈", AbdInvoker.invoke(220));
        equal(true, AbdInvoker.invoke(222));
        Address automatic = (Address) AbdInvoker.invoke(224);
        Address manual = (Address) AbdInvoker.invoke(225);
        equal(11, AbdInvoker.invoke(204, automatic));
        equal(33, AbdInvoker.invoke(204, manual));
        String report = Caller22220.globalMemories2Str();
        equal(true, report.contains("-1 :: type 0\n-2 :: type 1\n-3 :: type 4\n-4 :: type 7\n-5 :: type 7\n"));
        equal(true, AbdInvoker.saveStatus(saved));
        AbdInvoker.invoke(206, 99);
        AbdInvoker.invoke(221, "changed");
        AbdInvoker.invoke(223, false);
        Caller220.ACputMem(automatic.bits(), 77);
        AbdInvoker.invoke(201, 44);
        AbdInvoker.loadStatus(saved);
        equal(List.of(), destructed);
        equal(report, Caller22220.globalMemories2Str());

        File[] malformed = work.listFiles((directory, name) -> name.startsWith("numeric.invalid-") && name.endsWith(".snapshot.abd"));
        if (malformed == null || malformed.length < 8) throw new AssertionError("Missing malformed numeric fixtures");
        List<File> rejected = new ArrayList<>(List.of(malformed));
        for (int version : new int[]{0, 2, 3, 4, 5, 6}) rejected.add(new File(work, "numeric.named-v" + version + ".snapshot.abd"));
        for (File invalid : rejected) {
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(invalid));
            equal(7, AbdInvoker.invoke(207));
            equal("numeric\0中文🐈", AbdInvoker.invoke(220));
            equal(true, AbdInvoker.invoke(222));
            equal(automatic, AbdInvoker.invoke(224));
            equal(manual, AbdInvoker.invoke(225));
            equal(11, AbdInvoker.invoke(204, automatic));
            equal(33, AbdInvoker.invoke(204, manual));
            equal(List.of(), destructed);
        }
        AbdInvoker.loadStatus(new File(work, "numeric.valid.snapshot.abd"));
        equal(66, AbdInvoker.invoke(207));
        equal("restored", AbdInvoker.invoke(220));
        equal(false, AbdInvoker.invoke(222));
        equal(Address.of(1), AbdInvoker.invoke(224));
        equal(Address.of(2), AbdInvoker.invoke(225));
        equal(List.of(), destructed);
        AbdInvoker.invoke(203, Address.of(2));
        AbdInvoker.destroyScript();
        equal(List.of(33, 11), destructed);

        destructed.clear();
        loadAndFlush(new File(work, "numeric.other.exec.abd"));
        fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(saved));
        equal(7, AbdInvoker.invoke(207));
        equal("numeric\0中文🐈", AbdInvoker.invoke(220));
        AbdInvoker.destroyScript();
        equal(List.of(11), destructed);
        AbdInvoker.unregisterJfunction(0x34560004);
    }
    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        File script = new File(work, "fixture.exec.abd");
        File saved = new File(work, "快照 🐈.abd");
        try {
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(110));
            fails(IllegalStateException.class, () -> AbdInvoker.loadScript(new File(work, "missing.abd")));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadScript(new File(work, "broken.exec.abd")));
            AtomicInteger callbacks = new AtomicInteger();
            AbdInvoker.registerJfunction(new JfuncExecutor() {
                public int getId() { return 0x12340000; }
                public void run(Object[] values) { equal(0, values.length); callbacks.incrementAndGet(); }
            });
            AbdInvoker.registerJfunction(0x12340001, values -> (Integer) values[0] + (Integer) values[1]);
            loadAndFlush(script);
            fails(IllegalStateException.class, () -> AbdInvoker.loadScript(script));
            equal(42, AbdInvoker.invoke(110));
            equal(null, AbdInvoker.invoke(106));
            equal(-12345, AbdInvoker.invoke(101, -12345));
            equal("", AbdInvoker.invoke(102, ""));
            equal("中文\0emoji 🐈 café", AbdInvoker.invoke(102, "中文\0emoji 🐈 café"));
            equal(1.25f, AbdInvoker.invoke(103, 1.25f));
            equal(Math.PI, AbdInvoker.invoke(104, Math.PI));
            equal(true, AbdInvoker.invoke(105, true));
            equal(false, AbdInvoker.invoke(105, false));
            equal(42, AbdInvoker.invoke(109));
            equal(true, Address.NULL == Address.of(0));
            equal(false, Address.NULL.equals(0));
            equal("18446744073709551615", Address.of(-1L).toString());
            for (long bits : new long[]{0L, 1L, 0xffffffffL, 0x100000000L,
                    Long.MAX_VALUE, Long.MIN_VALUE, 0xfedcba9876543210L, -1L}) {
                Address value = Address.of(bits);
                equal(value, AbdInvoker.invoke(112, value));
                equal(bits, ((Address) AbdInvoker.invoke(112, value)).bits());
            }
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(112, 0));
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(112, (Object) null));
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(101, Address.of(1)));
            equal(null, AbdInvoker.invoke(0x12340000));
            equal(1, callbacks.get());
            AbdInvoker.registerJfunction(0x12340002, values -> values.length == 0 ? "零参数 🐈" : values[0]);
            equal("零参数 🐈", AbdInvoker.invoke(0x12340002));
            for (Object value : new Object[]{17, 1.5f, 3.25, true, false, "\0中文🐈", null,
                    Address.NULL, Address.of(0x100000000L), Address.of(Long.MIN_VALUE), Address.of(-1L)})
                equal(value, AbdInvoker.invoke(0x12340002, value));
            AbdInvoker.registerJfunction(0x12340003, values -> AbdInvoker.invoke(101, values[0]));
            equal(83, AbdInvoker.invoke(0x12340003, 83));
            // A block that a running script scope releases itself cannot be freed from a callback.
            AbdInvoker.registerJfunction(0x12340007, values -> {
                fails(IllegalArgumentException.class, () -> Caller20.memFree(((Address) values[0]).bits()));
                return null;
            });
            equal(7, AbdInvoker.invoke(111));
            fails(IllegalArgumentException.class, () -> AbdInvoker.registerJfunction(0x0fff0001, values -> null));
            fails(IllegalArgumentException.class, () -> AbdInvoker.registerJfunction(0x00000005, values -> null));
            AbdInvoker.registerJfunction(0x12340004, values -> { throw new UnsupportedOperationException("callback failure"); });
            AbdInvoker.registerJfunction(0x12340005, values -> new Object());
            AbdInvoker.registerJfunction(0x12340006, values -> { AbdInvoker.destroyScript(); return null; });
            AbdInvoker.registerJfunction(0x12340008, values -> Long.MIN_VALUE);
            fails(UnsupportedOperationException.class, () -> AbdInvoker.invoke(0x12340004, 1, true, ""));
            fails(IllegalArgumentException.class, () -> AbdInvoker.invoke(0x12340005));
            fails(IllegalArgumentException.class, () -> AbdInvoker.invoke(0x12340008));
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(0x12340006));
            fails(IllegalArgumentException.class, () -> AbdInvoker.invoke(0x1234ffff));
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(0x45670000));
            fails(IllegalArgumentException.class, () -> AbdInvoker.invoke(101, 1L));
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(101));
            fails(IllegalArgumentException.class, () -> Caller.call(1, null));
            fails(IndexOutOfBoundsException.class, () -> Caller.getMemType(-1));
            fails(IndexOutOfBoundsException.class, () -> Caller.getMemType(Long.MIN_VALUE));
            fails(IndexOutOfBoundsException.class, () -> Caller.getMemInt(Integer.MAX_VALUE));
            fails(IllegalArgumentException.class, () -> Caller20.memAlloc(-1));
            equal(0L, Caller20.memAlloc(0));
            Caller20.memFree(0);
            long slots = Caller20.memAlloc(8);
            Caller220.ACputMem(slots, "snapshot\0中文🐈");
            Caller220.ACputMem(slots + 1, true);
            Caller220.ACputMem(slots + 2, 3.25f);
            Caller220.ACputMem(slots + 3, 6.5);
            Caller220.ACputMem(slots + 4, 123);
            Caller220.ACputMemNull(slots + 5);
            Caller220.ACputMemAddress(slots + 6, 0xfedcba9876543210L);
            Caller220.ACputMemAddress(slots + 7, 0L);
            fails(IndexOutOfBoundsException.class, () -> Caller.getMemType((1L << 32) | slots));
            fails(IllegalArgumentException.class, () -> Caller20.memFree((1L << 32) | slots));
            equal(AbdInvoker.ADDRESS_VALUE, Caller.getMemType(slots + 6));
            equal(Address.of(0xfedcba9876543210L), AbdInvoker.readValue(slots + 6));
            equal(Address.NULL, AbdInvoker.readValue(slots + 7));
            fails(IllegalArgumentException.class, () -> Caller.getMemInt(slots + 6));
            fails(IllegalArgumentException.class, () -> Caller.getMemAddress(slots + 4));
            fails(IllegalArgumentException.class, () -> Caller.getMemInt(slots));
            fails(IllegalArgumentException.class, () -> Caller20.memFree(slots + 1));
            equal(41, AbdInvoker.invoke(107, 41));
            equal(true, AbdInvoker.saveStatus(saved));
            Caller220.ACputMem(slots, "changed");
            Caller220.ACputMem(slots + 1, false);
            Caller220.ACputMemAddress(slots + 6, 17L);
            Caller220.ACputMem(slots + 7, 0);
            AbdInvoker.invoke(107, 99);
            AbdInvoker.loadStatus(saved);
            equal("snapshot\0中文🐈", Caller.getMemStr(slots));
            equal(true, Caller.getMemBool(slots + 1));
            equal(3.25f, Caller.getMemFloat(slots + 2));
            equal(6.5, Caller.getMemDouble(slots + 3));
            equal(123, Caller.getMemInt(slots + 4));
            equal(null, AbdInvoker.readValue(slots + 5));
            equal(0xfedcba9876543210L, Caller.getMemAddress(slots + 6));
            equal(Address.of(0xfedcba9876543210L), AbdInvoker.readValue(slots + 6));
            equal(Address.NULL, AbdInvoker.readValue(slots + 7));
            equal(41, AbdInvoker.invoke(108));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(new File(work, "invalid.snapshot.abd")));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(new File(work, "overlap.snapshot.abd")));
            equal(41, AbdInvoker.invoke(108));
            equal("snapshot\0中文🐈", Caller.getMemStr(slots));
            Caller20.memFree(slots);
            fails(IllegalArgumentException.class, () -> Caller20.memFree(slots));
            String baseline = Caller22220.globalMemories2Str();
            for (int i = 0; i < 1000; i++) {
                equal(i, AbdInvoker.invoke(101, i));
                equal(i, AbdInvoker.invoke(0x12340002, i));
            }
            equal(baseline, Caller22220.globalMemories2Str());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> workers = new ArrayList<>();
                for (int thread = 0; thread < 4; thread++) workers.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        Object got = AbdInvoker.invoke(0x12340003, i);
                        if (!Integer.valueOf(i).equals(got)) throw new AssertionError(got);
                    }
                }));
                for (Future<?> worker : workers) worker.get();
            } finally { pool.shutdownNow(); }
            equal(baseline, Caller22220.globalMemories2Str());
            AbdInvoker.destroyScript();
            AbdInvoker.destroyScript();
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(101, 1));
            loadAndFlush(new File(work, "other.exec.abd"));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(saved));
            equal(42, AbdInvoker.invoke(110));
            AbdInvoker.destroyScript();
            loadAndFlush(script);
            equal(42, AbdInvoker.invoke(109));
            equal(90, AbdInvoker.invoke(107, 90));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(new File(work, "legacy.snapshot.abd")));
            equal(90, AbdInvoker.invoke(108));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(new File(work, "v2.snapshot.abd")));
            equal(90, AbdInvoker.invoke(108));
            fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(new File(work, "v3.snapshot.abd")));
            equal(90, AbdInvoker.invoke(108));
            AbdInvoker.unregisterJfunction(0x12340002);
            fails(IllegalArgumentException.class, () -> AbdInvoker.invoke(0x12340002));
            AbdInvoker.close();
            equal(0, Caller.namespaces.size());
            equal(0, Caller.executors.size());
            equal(0, Caller.returningExecutors.size());
            AbdInvoker.close();
            loadAndFlush(script);
            equal(42, AbdInvoker.invoke(110));
            AbdInvoker.destroyScript();
            AbdInvoker.registerJfunction(0x34560001, values -> { throw new UnsupportedOperationException("onclose failure"); });
            loadAndFlush(new File(work, "onclose.exec.abd"));
            fails(UnsupportedOperationException.class, AbdInvoker::close);
            equal(0, Caller.namespaces.size());
            fails(IllegalStateException.class, () -> AbdInvoker.invoke(110));
            fails(IllegalStateException.class, Caller22220::globalMemories2Str);
            AbdInvoker.registerJfunction(0x34560002, values -> { AbdInvoker.close(); return null; });
            AbdInvoker.loadScript(new File(work, "onload.exec.abd"));
            fails(IllegalStateException.class, AbdInvoker::flush);
            AbdInvoker.destroyScript();
            // Callbacks made while __script_onload runs can already use the script.
            AtomicInteger duringOnload = new AtomicInteger();
            AbdInvoker.registerJfunction(0x34560003, values -> {
                duringOnload.set((Integer) AbdInvoker.invoke(110));
                equal(false, AbdInvoker.saveStatus(saved));
                return null;
            });
            loadAndFlush(new File(work, "onload-invoke.exec.abd"));
            equal(42, duringOnload.get());
            AbdInvoker.destroyScript();
            objectSnapshots(work);
            numericSnapshots(work);
            checks += JniHintRegression.run(work);
            loadAndFlush(script);
            equal(42, AbdInvoker.invoke(110));
            System.out.println("JNI regression passed: " + checks + " assertions, 800 calls from 4 worker threads");
        } finally { AbdInvoker.close(); }
    }
}
