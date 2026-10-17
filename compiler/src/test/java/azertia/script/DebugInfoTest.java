package azertia.script;

import azertia.Main;
import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Exec v10 debug records: opt-in with -g, located by pre-order node numbers and never semantic. */
class DebugInfoTest {
    @TempDir Path temporary;
    private static final String PROBE = "int deep(int a){return 10 / a;}\n"
            + "int mid(int a){return deep(a - 1) + 1;}\n"
            + "int main(){print(mid(5));print(mid(1));return 0;}";
    private static final String CLASSES = "class P{int x=1;int y;P(int v){y=v;}~P(){}int operator+(int n){return x+n;}int get(){return x+y;}}\n"
            + "class Box<T>{T value;T get(){return value;}}\n"
            + "class Q{int z=2;}\n"
            + "int main(){P * p=new P(3);int total=0;for(int i=0;i<3;i=i+1){total+=p.get();}\n"
            + "Box<int> * b=new Box<int>();b.value=total;Q * z=new Q();int r=b.get()+z.z;delete z;delete b;delete p;return r;}";

    private static JsonObject tree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private static Map<String,AcsObject> functions(AcsObject program) {
        Map<String,AcsObject> result = new LinkedHashMap<>();
        for (AcsElement item : program.getAsAcsArray("f").acsa) {
            AcsObject function = (AcsObject) item;
            result.put(function.getAsAcsObject("debug").getAsString("name"), function);
        }
        return result;
    }
    /** An independent pre-order numbering of the inspection view: blocks, constants, calls and opcodes, children in wire order. */
    private static void number(AcsElement value, List<AcsElement> nodes) {
        nodes.add(value);
        if (value instanceof AcsArray block) { for (AcsElement item : block.acsa) number(item, nodes); return; }
        if (!(value instanceof AcsObject object)) return;
        if (object.getAsInt("t") == 1) { for (AcsElement item : object.getAsAcsArray("param").acsa) number(item, nodes); return; }
        int op = object.getAsInt("c");
        List<String> children = switch (op) {
            case ExecOpcodes.DEFINE, ExecOpcodes.SET -> List.of("val");
            case ExecOpcodes.RETURN, ExecOpcodes.RETURN_OBJECT -> List.of("r");
            case ExecOpcodes.IF -> List.of("v", "val", "else");
            case ExecOpcodes.WHILE, ExecOpcodes.CLEANUP -> List.of("v", "val");
            case ExecOpcodes.VALUE_COMPARE -> List.of("v1", "v2");
            case ExecOpcodes.BUFFER_OP -> List.of("v", "args");
            case ExecOpcodes.VARIABLE, ExecOpcodes.MOVE_OUT, ExecOpcodes.NEW_BLOCK, ExecOpcodes.BREAK, ExecOpcodes.CONTINUE,
                 ExecOpcodes.CONTEXT_ABI, ExecOpcodes.CONTEXT_DEFAULT, ExecOpcodes.BUFFER_NEW -> List.of();
            default -> object.mmp.containsKey("v1") ? List.of("v1", "v2") : List.of("v");
        };
        for (String key : children) {
            AcsElement child = object.mmp.get(key);
            if (child == null) continue;
            // Buffer arguments are an expression list, not a block.
            if (op == ExecOpcodes.BUFFER_OP && key.equals("args")) for (AcsElement item : ((AcsArray) child).acsa) number(item, nodes);
            else number(child, nodes);
        }
    }
    private static int[] location(AcsElement value) {
        return Arrays.stream(((AcsStringElement) value).s.split(":")).mapToInt(Integer::parseInt).toArray();
    }
    private static int column(String source, int line, String text) {
        return source.split("\n", -1)[line - 1].indexOf(text) + 1;
    }

    @Test void debugRecordsLocateTheFailingOperatorAndCallNodes() throws Exception {
        AcsObject compiled = Compiler.compile(tree(PROBE), true);
        assertEquals(List.of("<source>"), compiled.getAsAcsArray("debug-files").acsa.stream().map(e -> ((AcsStringElement) e).s).toList());
        Map<String,AcsObject> functions = functions(compiled);
        assertEquals(Set.of("deep", "mid", "main"), functions.keySet());
        AcsObject deep = functions.get("deep").getAsAcsObject("debug");
        assertEquals(List.of(0, 1, column(PROBE, 1, "(int a)")), List.of(deep.getAsInt("file"), deep.getAsInt("line"), deep.getAsInt("column")));
        // Each location names a pre-order node; the divide and the call to deep are located at their tokens.
        Map<String,AcsElement> located = new HashMap<>();
        for (var entry : functions.entrySet()) {
            List<AcsElement> nodes = new ArrayList<>(); number(entry.getValue().mmp.get("script"), nodes);
            int previous = -1;
            for (AcsElement value : entry.getValue().getAsAcsObject("debug").getAsAcsArray("locations").acsa) {
                int[] at = location(value);
                assertTrue(at[0] > previous && at[0] < nodes.size(), value.toString()); previous = at[0];
                located.put(entry.getKey() + "@" + at[2] + ":" + at[3], nodes.get(at[0]));
            }
        }
        AcsObject divide = (AcsObject) located.get("deep@1:" + column(PROBE, 1, "/"));
        assertEquals(ExecOpcodes.DIVIDE, divide.getAsInt("c"));
        AcsObject call = (AcsObject) located.get("mid@2:" + column(PROBE, 2, "deep("));
        assertEquals(1, call.getAsInt("t")); assertEquals(functions.get("deep").getAsInt("id"), call.getAsInt("id"));
        // Encoding, decoding and encoding again keep the bytes and the inspection view.
        AbdValue encoded = compiled.toValue(); AcsObject decoded = ExecCodec.decode(encoded);
        assertEquals(compiled.toJson(), decoded.toJson());
        assertArrayEquals(encoded.toAbdFormat(), decoded.toValue().toAbdFormat());
    }

    @Test void debugInfoIsOptInAndNeverChangesInstructions() throws Exception {
        for (String source : List.of(PROBE, CLASSES)) {
            JsonObject ast = tree(source);
            AcsObject plain = Compiler.compile(ast), debug = Compiler.compile(ast, true);
            assertTrue(plain.getAsAcsArray("debug-files").acsa.isEmpty());
            for (AcsElement function : plain.getAsAcsArray("f").acsa) assertFalse(((AcsObject) function).mmp.containsKey("debug"));
            assertFalse(Arrays.equals(plain.toValue().toAbdFormat(), debug.toValue().toAbdFormat()));
            List<AcsElement> withDebug = debug.getAsAcsArray("f").acsa;
            for (int i = 0; i < withDebug.size(); i++) {
                AcsObject copy = (AcsObject) ExecCodec.decode(debug.toValue()).getAsAcsArray("f").acsa.get(i);
                copy.mmp.remove("debug");
                assertEquals(((AcsObject) plain.getAsAcsArray("f").acsa.get(i)).toJson(), copy.toJson());
            }
            assertArrayEquals(plain.toValue().toAbdFormat(), Compiler.compile(ast, false).toValue().toAbdFormat());
        }
    }

    @Test void classNamesEnterOnlyDebugRecords() throws Exception {
        JsonObject ast = tree(CLASSES);
        String plain = new String(Compiler.compile(ast).toValue().getData(), StandardCharsets.ISO_8859_1);
        assertFalse(plain.contains("Box") || plain.contains("P::"), "class names stay in the compiler without -g");
        AcsObject debug = Compiler.compile(ast, true);
        for (AcsElement function : debug.getAsAcsArray("f").acsa) {
            String script = ((AcsObject) function).mmp.get("script").toJson().toString();
            assertFalse(script.contains("Box") || script.contains("P::"), script);
        }
        Set<String> names = functions(debug).keySet();
        for (String name : List.of("P::P", "P::~P", "P::operator+", "P::get", "Box::get", "<new P>", "<scoped P>", "<value P>", "<new Q>", "main"))
            assertTrue(names.contains(name), name + " in " + names);
        // A synthesized factory is positioned at its class declaration.
        AcsObject factory = functions(debug).get("<new Q>").getAsAcsObject("debug");
        assertEquals(List.of(3, column(CLASSES, 3, "Q{")), List.of(factory.getAsInt("line"), factory.getAsInt("column")));
    }

    @Test void readableAstRoundTripKeepsDebugBytes() throws Exception {
        for (String source : List.of(PROBE, CLASSES)) {
            JsonObject ast = tree(source);
            JsonObject reread = JsonParser.parseString(new com.google.gson.Gson().toJson(ast)).getAsJsonObject();
            assertArrayEquals(Compiler.compile(ast, true).toValue().toAbdFormat(), Compiler.compile(reread, true).toValue().toAbdFormat());
        }
    }

    @Test void includedFilesAreDeduplicatedDebugFiles() throws Exception {
        Files.writeString(temporary.resolve("lib.azs"), "#pragma once\nint helper(int a){\n  return 10 / a;\n}\n");
        Path main = temporary.resolve("main.azs");
        Files.writeString(main, "#include \"lib.azs\"\n#include \"lib.azs\"\nint main(){return helper(0);}\n");
        var script = new GeneraterJson.AzScript(); script.execute(main);
        AcsObject compiled = Compiler.compile(script.toObj(), true);
        List<String> files = compiled.getAsAcsArray("debug-files").acsa.stream().map(e -> ((AcsStringElement) e).s).toList();
        assertEquals(List.of("main.azs", "lib.azs"), files);
        AcsObject helper = functions(compiled).get("helper").getAsAcsObject("debug");
        assertEquals(List.of(1, 2), List.of(helper.getAsInt("file"), helper.getAsInt("line")));
        assertTrue(helper.getAsAcsArray("locations").acsa.stream().map(DebugInfoTest::location).anyMatch(at -> at[1] == 1 && at[2] == 3 && at[3] == 13));
    }

    @Test void debugNamesAndFilesAreSanitized() {
        assertEquals("a�b�c�", SourceRules.debugText("a\u0001b‮c\ud800"));
        assertEquals("😀é", SourceRules.debugText("😀é"));
        assertEquals("�", SourceRules.debugText(""));
        for (String bad : List.of("", "a\nb", "a\u0085", "a ", "a‮", "a⁦", "\ud800", "\u007f"))
            assertNotNull(SourceRules.debugTextProblem(bad), bad);
        assertEquals("C::C", Compiler.debugName("C::<ctor>")); assertEquals("C::~C", Compiler.debugName("C::<dtor>"));
        assertEquals("C::operator[]=", Compiler.debugName("C::<op:index-set>")); assertEquals("<placement C>", Compiler.debugName("C::<placement>"));
        assertEquals("main", Compiler.debugName("main"));
    }

    private static AcsObject debugProgram() throws Exception { return Compiler.compile(tree(PROBE), true); }
    private static AcsObject firstDebug(AcsObject program) {
        return ((AcsObject) program.getAsAcsArray("f").acsa.get(0)).getAsAcsObject("debug");
    }
    private static AcsArray strings(String... values) {
        AcsArray array = new AcsArray(); for (String value : values) array.acsa.add(new AcsStringElement(value)); return array;
    }

    @Test void malformedDebugInspectionViewsAreRejected() throws Exception {
        List<Consumer<AcsObject>> mutations = List.of(
                p -> p.mmp.remove("debug-files"),
                p -> p.put("debug-files", new AcsArray()),
                p -> p.put("debug-files", strings("")),
                p -> p.put("debug-files", strings("a", "a")),
                p -> p.put("debug-files", strings("a\nb")),
                p -> p.put("debug-files", strings("a‮")),
                p -> p.put("debug-files", strings("\ud800")),
                p -> firstDebug(p).put("extra", 1),
                p -> firstDebug(p).mmp.remove("locations"),
                p -> firstDebug(p).put("name", ""),
                p -> firstDebug(p).put("name", "a\u0001"),
                p -> firstDebug(p).put("file", 1),
                p -> firstDebug(p).put("file", -1),
                p -> firstDebug(p).put("line", 0),
                p -> { firstDebug(p).put("line", 0); firstDebug(p).put("column", 3); },
                p -> firstDebug(p).put("column", 0),
                p -> firstDebug(p).put("line", -1),
                p -> firstDebug(p).put("locations", strings("1:0:1")),
                p -> firstDebug(p).put("locations", strings("1:0:1:x")),
                p -> firstDebug(p).put("locations", strings("01:0:1:1")),
                p -> firstDebug(p).put("locations", strings("-1:0:1:1")),
                p -> firstDebug(p).put("locations", strings("1:1:1:1")),
                p -> firstDebug(p).put("locations", strings("1:0:0:1")),
                p -> firstDebug(p).put("locations", strings("1:0:1:0")),
                p -> firstDebug(p).put("locations", strings("2:0:1:1", "1:0:1:1")),
                p -> firstDebug(p).put("locations", strings("1:0:1:1", "1:0:1:2")),
                p -> firstDebug(p).put("locations", strings("1000:0:1:1")),
                p -> firstDebug(p).put("locations", strings("99999999999:0:1:1")),
                p -> { AcsArray locations = new AcsArray(); locations.acsa.add(new AcsIntegerElement(1)); firstDebug(p).put("locations", locations); },
                p -> ((AcsObject) p.getAsAcsArray("f").acsa.get(0)).put("debug", 1),
                p -> p.put("exec-version", 9));
        for (int i = 0; i < mutations.size(); i++) {
            AcsObject program = debugProgram(); mutations.get(i).accept(program);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, program::toValue, "mutation " + i);
            assertTrue(error.getMessage().startsWith("Invalid exec: "), error.getMessage());
        }
        // A node count equal to the body's node count is the first missing node.
        AcsObject program = debugProgram(); AcsObject function = (AcsObject) program.getAsAcsArray("f").acsa.get(0);
        List<AcsElement> nodes = new ArrayList<>(); number(function.mmp.get("script"), nodes);
        firstDebug(program).put("locations", strings((nodes.size() - 1) + ":0:1:1")); program.toValue();
        firstDebug(program).put("locations", strings(nodes.size() + ":0:1:1"));
        assertThrows(IllegalArgumentException.class, program::toValue);
    }

    private static AbdValue raw(List<AbdValue> fields) { AbdSimpleStack stack = new AbdSimpleStack(); stack.values = new ArrayList<>(fields); return stack.toAbd(); }
    private static AbdValue replaceFunction(AbdValue program, java.util.function.UnaryOperator<List<AbdValue>> change) {
        List<AbdValue> root = new ArrayList<>(program.getAsAss().values), functions = new ArrayList<>(root.get(7).getAsAss().values);
        functions.set(0, raw(change.apply(new ArrayList<>(functions.get(0).getAsAss().values))));
        root.set(7, raw(functions)); return raw(root);
    }

    @Test void malformedDebugWireRecordsAreRejected() throws Exception {
        AbdValue good = debugProgram().toValue(), plain = Compiler.compile(tree(PROBE)).toValue();
        List<AbdValue> cases = new ArrayList<>();
        List<AbdValue> shortRoot = new ArrayList<>(good.getAsAss().values); shortRoot.remove(10); cases.add(raw(shortRoot));
        List<AbdValue> longRoot = new ArrayList<>(good.getAsAss().values); longRoot.add(raw(List.of())); cases.add(raw(longRoot));
        cases.add(replaceFunction(plain, f -> { f.set(8, AbdBasicType.bol2Abd(true)); return f; }));
        cases.add(replaceFunction(good, f -> { f.remove(9); return f; }));
        cases.add(replaceFunction(good, f -> { f.set(8, AbdBasicType.bol2Abd(false)); return f; }));
        cases.add(replaceFunction(good, f -> { f.add(AbdBasicType.int2Abd(0)); return f; }));
        cases.add(replaceFunction(good, f -> {
            List<AbdValue> debug = new ArrayList<>(f.get(9).getAsAss().values); debug.remove(4); f.set(9, raw(debug)); return f;
        }));
        cases.add(replaceFunction(good, f -> {
            List<AbdValue> debug = new ArrayList<>(f.get(9).getAsAss().values);
            List<AbdValue> quads = new ArrayList<>(debug.get(4).getAsAss().values); quads.remove(quads.size() - 1);
            debug.set(4, raw(quads)); f.set(9, raw(debug)); return f;
        }));
        List<AbdValue> oldVersion = new ArrayList<>(good.getAsAss().values); oldVersion.set(1, AbdBasicType.int2Abd(9)); cases.add(raw(oldVersion));
        for (AbdValue value : cases) assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(value));
        IllegalArgumentException version = assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(raw(oldVersion)));
        assertEquals("Invalid exec: unsupported exec version 9; expected exec v10", version.getMessage());
    }

    @Test void theGOptionAddsDebugRecordsToCompileAndCompileJson() throws Exception {
        Path source = temporary.resolve("probe.azs"); Files.writeString(source, PROBE);
        Path plain = temporary.resolve("plain.abd"), debug = temporary.resolve("debug.abd"), ast = temporary.resolve("probe.json");
        Path fromJson = temporary.resolve("json.abd"), late = temporary.resolve("late.abd");
        var bytes = new ByteArrayOutputStream(); var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "-o", plain.toString(), "--ast", ast.toString()}, stream, stream), bytes::toString);
        assertEquals(0, Main.run(new String[]{"compile", "-g", source.toString(), "-o", debug.toString()}, stream, stream), bytes::toString);
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "-o", late.toString(), "-g"}, stream, stream), bytes::toString);
        assertEquals(0, Main.run(new String[]{"compile-json", ast.toString(), "-g", "-o", fromJson.toString()}, stream, stream), bytes::toString);
        assertTrue(ExecCodec.decode(Files.readAllBytes(plain)).getAsAcsArray("debug-files").acsa.isEmpty());
        AcsObject decoded = ExecCodec.decode(Files.readAllBytes(debug));
        assertEquals(List.of("probe.azs"), decoded.getAsAcsArray("debug-files").acsa.stream().map(e -> ((AcsStringElement) e).s).toList());
        assertArrayEquals(Files.readAllBytes(debug), Files.readAllBytes(late));
        assertArrayEquals(Files.readAllBytes(debug), Files.readAllBytes(fromJson));
        assertEquals(1, Main.run(new String[]{"compile", source.toString(), "-g3"}, stream, stream));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Unknown option: -g3"));
        var help = new ByteArrayOutputStream(); Main.run(new String[]{"--help"}, new PrintStream(help, true, StandardCharsets.UTF_8), stream);
        assertTrue(help.toString(StandardCharsets.UTF_8).contains("[-g]"));
    }
}
