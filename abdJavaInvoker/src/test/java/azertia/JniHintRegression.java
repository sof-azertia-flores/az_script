package azertia;

import azertia.jni.Caller;
import azertia.jni.Caller20;
import azertia.jni.Caller220;
import azertia.jni.Caller22220;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Linking and state replacement against independently encoded real modules. */
final class JniHintRegression {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void fails(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName());
    }
    static int run(File work) throws Exception {
        File consumer = new File(work, "hints.consumer.exec.abd");
        File library = new File(work, "hints.library.exec.abd");
        File extra = new File(work, "hints.extra.exec.abd");
        File saved = new File(work, "hints.pair.snapshot.abd");
        File all = new File(work, "hints.all.snapshot.abd");
        List<Integer> events = new ArrayList<>();
        List<Integer> destructed = new ArrayList<>();
        AbdInvoker.registerJfunction(0x34560004, args -> { destructed.add((Integer) args[0]); return null; });
        AbdInvoker.registerJfunction(0x34560010, args -> {
            int event = (Integer) args[0];
            events.add(event);
            if (event == 10) {
                int ns = AbdInvoker.namespaceForHint("PointLibrary");
                equal(42, AbdInvoker.invoke((ns << 16) | 2));
                equal(false, AbdInvoker.saveStatus(saved));
                fails(IllegalStateException.class, () -> AbdInvoker.loadStatus(saved));
                fails(IllegalStateException.class, () -> AbdInvoker.insertScript(extra));
                fails(IllegalStateException.class, AbdInvoker::flush);
                fails(IllegalStateException.class, Caller::flush);
                fails(IllegalStateException.class, () -> Caller.insertScript(extra.getAbsolutePath()));
                fails(IllegalStateException.class, () -> Caller22220.saveStatusToFile(saved.getAbsolutePath()));
            }
            return null;
        });
        AbdInvoker.loadScript(consumer);
        equal(List.of(), events);
        fails(IllegalStateException.class, () -> AbdInvoker.invoke(0x12000002));
        fails(IllegalStateException.class, () -> AbdInvoker.saveStatus(saved));
        fails(IllegalStateException.class, () -> AbdInvoker.loadStatus(saved));
        fails(RuntimeException.class, () -> AbdInvoker.namespaceForHint("PointLibrary"));
        fails(RuntimeException.class, AbdInvoker::flush);
        equal(List.of(), events);
        AbdInvoker.insertScript(library);
        equal(1, AbdInvoker.namespaceForHint("PointLibrary"));
        fails(RuntimeException.class, () -> AbdInvoker.insertScript(library));
        fails(IllegalStateException.class, () -> AbdInvoker.invoke(0x12000002));
        AbdInvoker.flush();
        equal(List.of(10, 100), events);
        equal(42, AbdInvoker.invoke(0x12000002));
        Address automatic = (Address) AbdInvoker.invoke(0x12000003);
        Address manual = (Address) AbdInvoker.invoke(0x12000004);
        equal(11, AbdInvoker.invoke(0x12000005, automatic));
        equal(33, AbdInvoker.invoke(0x12000005, manual));
        AbdInvoker.flush();
        equal(List.of(10, 100), events);
        equal(true, AbdInvoker.saveStatus(saved));
        AbdInvoker.invoke(0x00010006, 99);
        Caller220.ACputMem(automatic.bits(), 77);
        AbdInvoker.loadStatus(saved);
        equal(42, AbdInvoker.invoke(0x00010002));
        equal(11, AbdInvoker.invoke(0x12000005, automatic));
        equal(List.of(10, 100), events);
        equal(List.of(), destructed);

        AbdInvoker.insertScript(extra);
        equal(2, AbdInvoker.namespaceForHint("ExtraLibrary"));
        fails(IllegalStateException.class, () -> AbdInvoker.invoke(0x12000002));
        fails(IllegalStateException.class, () -> AbdInvoker.saveStatus(saved));
        fails(IllegalStateException.class, () -> AbdInvoker.loadStatus(saved));
        AbdInvoker.flush();
        equal(List.of(10, 100, 200), events);
        equal(88, AbdInvoker.invoke(0x00020002));
        fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(saved));
        equal(42, AbdInvoker.invoke(0x12000002));
        equal(11, AbdInvoker.invoke(0x12000005, automatic));
        equal(true, AbdInvoker.saveStatus(all));
        AbdInvoker.invoke(0x12000006, manual);
        equal(List.of(33), destructed);
        AbdInvoker.destroyScript();
        equal(List.of(33, 11), destructed);
        equal(true, events.containsAll(List.of(20, 110, 210)));

        // The same ordered manifest restores without rerunning initialization.
        events.clear(); destructed.clear();
        AbdInvoker.loadScript(consumer);
        AbdInvoker.insertScript(library);
        AbdInvoker.insertScript(extra);
        AbdInvoker.flush();
        List<Integer> initialized = List.copyOf(events);
        AbdInvoker.loadStatus(all);
        equal(initialized, events);
        equal(List.of(), destructed);
        equal(33, AbdInvoker.invoke(0x12000005, (Address) AbdInvoker.invoke(0x12000004)));
        AbdInvoker.destroyScript();
        equal(List.of(11), destructed);

        // Equal schemas do not make different module bytes or load order identical.
        events.clear(); destructed.clear();
        AbdInvoker.loadScript(consumer);
        AbdInvoker.insertScript(new File(work, "hints.changed-library.exec.abd"));
        AbdInvoker.insertScript(extra);
        AbdInvoker.flush();
        fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(all));
        equal(42, AbdInvoker.invoke(0x12000002));
        AbdInvoker.destroyScript();
        AbdInvoker.loadScript(library);
        AbdInvoker.insertScript(consumer);
        AbdInvoker.insertScript(extra);
        AbdInvoker.flush();
        fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(all));
        equal(42, AbdInvoker.invoke(0x12000002));
        AbdInvoker.destroyScript();

        // A registered host namespace changes allocation; snapshots retain that identity.
        AbdInvoker.registerJfunction(0x00010001, args -> null);
        AbdInvoker.loadScript(consumer);
        AbdInvoker.insertScript(library);
        AbdInvoker.insertScript(extra);
        equal(2, AbdInvoker.namespaceForHint("PointLibrary"));
        equal(3, AbdInvoker.namespaceForHint("ExtraLibrary"));
        AbdInvoker.flush();
        fails(IllegalArgumentException.class, () -> AbdInvoker.loadStatus(all));
        equal(42, AbdInvoker.invoke(0x12000002));
        AbdInvoker.destroyScript();
        AbdInvoker.unregisterJfunction(0x00010001);
        AbdInvoker.unregisterJfunction(0x34560010);

        // 0xffffffff is an actual destructor ID; absence is represented separately.
        destructed.clear();
        AbdInvoker.loadScript(new File(work, "high-destructor.exec.abd"));
        AbdInvoker.flush();
        Address high = (Address) AbdInvoker.invoke(0x7FFF0002);
        File highSaved = new File(work, "high-destructor.snapshot.abd");
        equal(true, AbdInvoker.saveStatus(highSaved));
        Caller220.ACputMem(high.bits(), 99);
        AbdInvoker.loadStatus(highSaved);
        equal(55, Caller.getMemInt(high.bits()));
        equal(List.of(), destructed);
        AbdInvoker.destroyScript();
        equal(List.of(55), destructed);
        AbdInvoker.unregisterJfunction(0x34560004);

        // Failed oversized serialization leaves the previous complete file intact.
        AbdInvoker.loadScript(new File(work, "fixture.exec.abd"));
        AbdInvoker.flush();
        File bounded = new File(work, "bounded.snapshot.abd");
        equal(true, AbdInvoker.saveStatus(bounded));
        byte[] previous = Files.readAllBytes(bounded.toPath());
        long large = Caller20.memAlloc(2);
        try {
            String value = "x".repeat(33 * 1024 * 1024);
            Caller220.ACputMem(large, value);
            Caller220.ACputMem(large + 1, value);
            fails(RuntimeException.class, () -> AbdInvoker.saveStatus(bounded));
            equal(true, Arrays.equals(previous, Files.readAllBytes(bounded.toPath())));
            equal(42, AbdInvoker.invoke(110));
        } finally { Caller20.memFree(large); }
        AbdInvoker.destroyScript();
        return checks;
    }
}
