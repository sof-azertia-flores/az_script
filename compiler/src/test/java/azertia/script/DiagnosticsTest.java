package azertia.script;

import azertia.Main;
import com.google.gson.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import azertia.binary.complexBinary.AcsArray;
import azertia.binary.complexBinary.AcsObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Diagnostics name the original file, line and column through includes and macros. */
class DiagnosticsTest {
    @TempDir Path temporary;

    private Path write(String name, String content) throws Exception {
        Path path = temporary.resolve(name); Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8); return path;
    }
    private CompileException failure(Path source) {
        return assertThrows(CompileException.class, () -> {
            var script = new GeneraterJson.AzScript(); script.execute(source); Compiler.compile(script.toObj());
        });
    }
    private CompileException failure(String source) {
        return assertThrows(CompileException.class, () -> {
            var script = new GeneraterJson.AzScript(); script.execute(source); Compiler.compile(script.toObj());
        });
    }
    private JsonObject tree(Path source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private JsonObject tree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private static JsonObject function(JsonObject tree, String name) {
        for (JsonElement namespace : tree.getAsJsonObject("body").asMap().values())
            for (JsonElement value : namespace.getAsJsonObject().asMap().values())
                if (value.getAsJsonObject().getAsJsonObject("metadata").get("name").getAsString().equals(name)) return value.getAsJsonObject();
        throw new AssertionError("Missing function " + name);
    }
    private static String run(String... args) {
        var bytes = new ByteArrayOutputStream(); var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Main.run(args, stream, stream); return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void semanticErrorInAnIncludedFileNamesThatFileAndTheIncludeSite() throws Exception {
        write("inc/lib.azs", "// line 1\nint helper(int a) {\n  return a + 1;\n}\nint broken(int a) {\n  string s = a;\n  return 0;\n}\n");
        Path main = write("main.azs", "// main file line 1\n#include \"inc/lib.azs\"\nint main() {\n  return helper(1);\n}\n");
        CompileException error = failure(main);
        assertEquals("inc/lib.azs:6:3: error: in function broken: Initializer for s expected string, got int\n"
                + "main.azs:2:1: note: in file included from here", error.getMessage());
        assertEquals(1, error.errors().size());
        Diagnostic diagnostic = error.errors().get(0);
        assertEquals(new Diagnostic.Position("inc/lib.azs", 6, 3), diagnostic.position());
        assertEquals("function broken", diagnostic.context());
    }

    @Test void syntaxErrorInAnIncludedFileKeepsItsOwnLines() throws Exception {
        write("inc/lib2.azs", "int helper2(int a) {\n  return a +* 1;\n}\n");
        Path main = write("main.azs", "// line 1\n// line 2\n#include \"inc/lib2.azs\"\nint main() {\n  return helper2(1);\n}\n");
        assertEquals("inc/lib2.azs:2:13: error: Expected expression, found '*'\nmain.azs:3:1: note: in file included from here",
                failure(main).getMessage());
    }

    @Test void largeHeadersDoNotShiftLaterLines() throws Exception {
        Files.createDirectories(temporary.resolve("lib"));
        Files.copy(Path.of("stdlib/math.include.azs"), temporary.resolve("lib/math.include.azs"));
        Path main = write("main.azs", "#include \"lib/math.include.azs\"\nint main() {\n  return 1 +;\n}\n");
        assertEquals("main.azs:3:13: error: Expected expression, found ';'", failure(main).getMessage());
    }

    @Test void nestedIncludeNotesAreInnermostFirst() throws Exception {
        write("inc/c.azs", "int f(){\n  return \"s\";\n}\n");
        write("inc/b.azs", "// b\n#include \"c.azs\"\n");
        Path main = write("a.azs", "#include \"inc/b.azs\"\nint main(){return f();}\n");
        CompileException error = failure(main);
        assertEquals("inc/c.azs:2:3: error: in function f: return expected int, got string\n"
                + "inc/b.azs:2:1: note: in file included from here\n"
                + "a.azs:1:1: note: in file included from here", error.getMessage());
        List<Diagnostic> notes = error.errors().get(0).notes();
        assertEquals(List.of("inc/b.azs", "a.azs"), notes.stream().map(note -> note.position().file()).toList());
        assertTrue(notes.stream().allMatch(note -> note.severity() == Diagnostic.Severity.NOTE));
    }

    @Test void macroExpansionsReportTheUseColumnAndTheDefinition() throws Exception {
        assertEquals("<source>:2:21: error: Expected expression, found '*'\n"
                        + "<source>:1:1: note: in expansion of macro 'BAD' defined here",
                failure("#define BAD + *\nint main(){return 1 BAD;}\n").getMessage());
        // Tokens after an expansion keep their own columns.
        assertEquals("<source>:2:30: error: in function main: Unknown variable: missing",
                failure("#define N 7\nint main(){ return N + \"x\" + missing; }\n").getMessage());
        // Expansion errors are located at the macro use.
        assertEquals("<source>:4:14: error: Recursive macro: X",
                failure("#define X Y\n#define Y X\nint main(){\n  return 1 + X;\n}\n").getMessage());
        // Semantic errors in expanded code are located at the use, without a macro note.
        assertEquals("<source>:3:10: error: in function main: Unknown variable: missing",
                failure("#define V missing\nint main(){\n  return V;\n}\n").getMessage());
    }

    @Test void macroNotesFollowTheIncludeChainOfTheirOwnFile() throws Exception {
        // The error's include chain comes first; a #define in another file adds that file's chain.
        write("inc/defs.azs", "// defs\n#define BAD + *\n");
        Path header = write("header.azs", "#include \"inc/defs.azs\"\nint main(){\n  return 1 BAD;\n}\n");
        assertEquals("header.azs:3:12: error: Expected expression, found '*'\n"
                + "inc/defs.azs:2:1: note: in expansion of macro 'BAD' defined here\n"
                + "header.azs:1:1: note: in file included from here", failure(header).getMessage());
        write("inc/use.azs", "int u(){\n  return 1 MAIN_BAD;\n}\n");
        Path main = write("use.azs", "#define MAIN_BAD + *\n#include \"inc/use.azs\"\nint main(){return 0;}\n");
        assertEquals("inc/use.azs:2:12: error: Expected expression, found '*'\n"
                + "use.azs:2:1: note: in file included from here\n"
                + "use.azs:1:1: note: in expansion of macro 'MAIN_BAD' defined here", failure(main).getMessage());
    }

    @Test void columnMappingStaysLinearOnLinesWithManyExpansions() {
        // 400k expansions on one line; a per-token linear scan of the line's column runs took minutes.
        String source = "#define A 1\nint main(){ return 0; }\n" + "A ".repeat(400_000) + "\n";
        CompileException error = assertTimeout(Duration.ofSeconds(20), () -> failure(source));
        assertTrue(error.getMessage().startsWith("<source>:3:1: error: "), error.getMessage());
    }

    @Test void lexerAndPreprocessorErrorsNameTheirFile() throws Exception {
        assertEquals("<source>:2:3: error: Unexpected character: @", failure("void main(){\n  @;\n}\n").getMessage());
        write("inc/lex.azs", "int f(){\n  return 1 @ 2;\n}\n");
        Path lexical = write("lexical.azs", "#include \"inc/lex.azs\"\nint main(){return 0;}\n");
        assertEquals("inc/lex.azs:2:12: error: Unexpected character: @\nlexical.azs:1:1: note: in file included from here",
                failure(lexical).getMessage());
        assertEquals("<source>:2:10: error: Numeric literal is out of range",
                failure("int main(){\n  return 1e99999999999;\n}\n").getMessage());
        write("inc/x.azs", "\n  #bogus\n");
        Path unknown = write("unknown.azs", "#include \"inc/x.azs\"\nint main(){return 0;}\n");
        assertEquals("inc/x.azs:2:3: error: Unknown preprocessor directive: #bogus\nunknown.azs:1:1: note: in file included from here",
                failure(unknown).getMessage());
        // An unclosed condition is reported at its own directive, an unterminated comment where it starts.
        assertEquals("<source>:2:1: error: Unclosed preprocessor condition",
                failure("int main(){return 0;}\n#ifdef A\n#ifdef B\n#endif\n#ifdef C\n#endif\n").getMessage());
        assertEquals("<source>:2:3: error: Unclosed preprocessor condition",
                failure("int main(){return 0;}\n  #ifdef A\n").getMessage());
        assertEquals("<source>:2:3: error: Unterminated block comment", failure("int main(){return 0;}\n  /* open\n comment\n").getMessage());
        Path missing = write("missing.azs", "int main(){return 0;}\n#include \"nothere.azs\"\n");
        assertEquals("missing.azs:2:1: error: Cannot open include file \"nothere.azs\": file not found", failure(missing).getMessage());
        write("inc/a.azs", "#include \"b.azs\"\n"); write("inc/b.azs", "#include \"a.azs\"\n");
        Path cycle = write("cycle.azs", "#include \"inc/a.azs\"\n");
        assertEquals("inc/b.azs:1:1: error: Include cycle: inc/a.azs -> inc/b.azs -> inc/a.azs\n"
                + "inc/a.azs:1:1: note: in file included from here\ncycle.azs:1:1: note: in file included from here",
                failure(cycle).getMessage());
        Path self = write("self.azs", "#include self.azs\n");
        assertEquals("self.azs:1:1: error: Include cycle: self.azs -> self.azs", failure(self).getMessage());
        Path binary = temporary.resolve("binary.azs"); Files.write(binary, new byte[]{'i', 'n', 't', (byte) 0xff});
        assertEquals("binary.azs: error: Source file \"binary.azs\" is not valid UTF-8", failure(binary).getMessage());
        Path includesBinary = write("includes-binary.azs", "#include \"binary.azs\"\n");
        assertEquals("includes-binary.azs:1:1: error: Source file \"binary.azs\" is not valid UTF-8", failure(includesBinary).getMessage());
    }

    @Test void formerlyUnlocatedErrorsAreLocated() throws Exception {
        assertEquals("<source>:2:1: error: in function main: AST nesting exceeds 320 levels",
                failure("int main(){\nreturn " + "1+".repeat(2000) + "1;}").getMessage());
        // The innermost enclosing statement is named, not the outer if.
        assertEquals("<source>:5:5: error: in function main: AST nesting exceeds 320 levels",
                failure("int main(){\n  int x = 0;\n\n  if (true) {\n    int y = " + "1+".repeat(400) + "1;\n  }\n  return 0;\n}\n").getMessage());
        // Field initializers and base-initializer arguments are bounded before the AST is copied.
        assertEquals("<source>:3:3: error: in initializer of class P: AST nesting exceeds 320 levels",
                failure("class P {\n  int a;\n  int v = " + "1+".repeat(3000) + "1;\n}\nvoid main(){ P p; }\n").getMessage());
        assertEquals("<source>:4:4: error: in function D::D: AST nesting exceeds 320 levels",
                failure("class B{int v;B(int x){v=x;}}\nclass D:B{\n  int w;\n  D():B(" + "1+".repeat(3000) + "1){}\n}\nvoid main(){ D d; }\n").getMessage());
        assertEquals("<source>:2:6: error: in function A::~A: Unregistered class function: A::<dtor>",
                failure("class A{int x;}\nA::~A(){}\nvoid main(){}").getMessage());
        assertEquals("<source>:3:1: error: in function g: non-void function can reach the end of its body without returning a value",
                failure("int g(){\n  if (true) { return 1; }\n}\nint main(){return g();}").getMessage());
        JsonObject unknownType = tree("class P{\n  int x;\n}\nvoid main(){P p;}");
        unknownType.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("type", "T");
        CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(unknownType));
        assertEquals("<source>:2:3: error: in class P: Unknown type: T", error.getMessage());
        // Errors without a node are reported for the input file.
        JsonObject global = tree("class P{int x;}\nvoid main(){}");
        global.getAsJsonArray("global-variable").add("P");
        assertEquals("<source>: error: in global P: Global name conflicts with class: P",
                assertThrows(CompileException.class, () -> Compiler.compile(global)).getMessage());
        // A parameter error is located at its function.
        JsonObject parameters = tree("int add(int a, int b){return a+b;}\nint main(){return add(1,2);}");
        function(parameters, "add").getAsJsonObject("metadata").add("param", JsonParser.parseString("[\"a\",\"a\"]"));
        assertEquals("<source>:1:8: error: in function add: Duplicate variable in the same scope: a",
                assertThrows(CompileException.class, () -> Compiler.compile(parameters)).getMessage());
        // A bare expression environment names its source <expression>, like getExpression.
        var script = new GeneraterJson.AzScript();
        JsonElement expression = GeneraterJson.getExpression("missing + 1", 0, script).generate();
        assertEquals("<expression>:1:1: error: Unknown variable: missing", assertThrows(CompileException.class,
                () -> Compiler.compileExpression(expression, new Compiler.LogicEnvironment())).getMessage());
        assertEquals("<expression>:1:5: error: Expected expression, found '*'",
                assertThrows(CompileException.class, () -> GeneraterJson.getExpression("1 + *", 0, script)).getMessage());
        // A path without a file name is an I/O error, not a crash.
        if (FileSystems.getDefault().getSeparator().equals("/"))
            assertThrows(IOException.class, () -> new GeneraterJson.AzScript().execute(Path.of("/")));
    }

    @Test void errorsWithoutAPositionNameTheCompiledInput() throws Exception {
        Path main = write("named.azs", "class P{int x;}\nint main(){\n  return 0;\n}\n");
        JsonObject tree = tree(main);
        tree.getAsJsonArray("global-variable").add("P");
        assertEquals("named.azs: error: in global P: Global name conflicts with class: P",
                assertThrows(CompileException.class, () -> Compiler.compile(tree)).getMessage());
        // compile-json names its JSON input, while positioned errors still name the original source.
        assertEquals("tree.json: error: in global P: Global name conflicts with class: P", assertThrows(CompileException.class,
                () -> Compiler.compile(tree, new Diagnostics().defaultFile("tree.json"))).getMessage());
        JsonObject noMetadata = tree(main); noMetadata.remove("metadata");
        assertEquals("tree.json: error: Malformed script JSON at /metadata: required field is missing",
                assertThrows(CompileException.class, () -> Compiler.compile(noMetadata, new Diagnostics().defaultFile("tree.json"))).getMessage());
        JsonObject positioned = tree(main);
        function(positioned, "main").getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").set(0, JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"missing\"],\"_line\":3,\"_column\":10}"));
        assertEquals("named.azs:3:10: error: in function main: Unknown variable: missing",
                assertThrows(CompileException.class, () -> Compiler.compile(positioned, new Diagnostics().defaultFile("tree.json"))).getMessage());
        // One collector serves one compilation, so earlier errors never leak into a later report.
        Diagnostics used = new Diagnostics();
        assertThrows(CompileException.class, () -> Compiler.compile(tree, used));
        assertThrows(IllegalStateException.class, () -> Compiler.compile(tree(main), used));
        assertEquals(1, used.diagnostics().size());
    }

    @Test void headerDeclarationsAndNodesCarryTheirSourceUnit() throws Exception {
        write("inc/shapes.azs", "class B{int v;B(int x){v=x;}}\nclass D:B{\n  float f=1.5f;\n  int w=1+2;\n  D():B(1+1){}\n}\n");
        Path main = write("main.azs", "#include \"inc/shapes.azs\"\nvoid main(){D d;}\n");
        JsonObject tree = tree(main);
        assertEquals(JsonParser.parseString("[{\"name\":\"main.azs\"},{\"name\":\"inc/shapes.azs\",\"included-from\":{\"source\":0,\"line\":1,\"column\":1}}]"),
                tree.get("sources"));
        JsonObject derived = tree.getAsJsonArray("classes").get(1).getAsJsonObject();
        assertEquals(1, derived.get("_file").getAsInt());
        JsonArray fields = derived.getAsJsonArray("fields");
        JsonObject floating = fields.get(0).getAsJsonObject().getAsJsonObject("initializer");
        assertEquals("float", floating.get("call").getAsString());
        assertEquals(List.of(1, 3, 11), List.of(floating.get("_file").getAsInt(), floating.get("_line").getAsInt(), floating.get("_column").getAsInt()));
        assertEquals(1, fields.get(1).getAsJsonObject().get("_file").getAsInt());
        assertEquals(1, fields.get(1).getAsJsonObject().getAsJsonObject("initializer").get("_file").getAsInt());
        JsonObject constructor = function(tree, "D::<ctor>").getAsJsonObject("metadata");
        assertEquals(List.of(1, 5, 4, 5, 14), List.of(constructor.get("_file").getAsInt(), constructor.get("_line").getAsInt(),
                constructor.get("_column").getAsInt(), constructor.get("_end_line").getAsInt(), constructor.get("_end_column").getAsInt()));
        assertEquals(1, constructor.getAsJsonArray("base-args").get(0).getAsJsonObject().get("_file").getAsInt());
        // Main-file nodes omit _file.
        JsonObject mainMeta = function(tree, "main").getAsJsonObject("metadata");
        assertFalse(mainMeta.has("_file"));
        assertFalse(function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject().has("_file"));
        // A base-initializer error inside the header is located there.
        write("inc/base.azs", "class B{int v;B(int x){v=x;}}\nclass D:B{\n  int w;\n  D():B(\"x\"){}\n}\n");
        Path broken = write("broken.azs", "#include \"inc/base.azs\"\nvoid main(){D d;}\n");
        assertEquals("inc/base.azs:4:4: error: in function D::D: B::<ctor> argument 1 expected int, got string\n"
                + "broken.azs:1:1: note: in file included from here", failure(broken).getMessage());
    }

    @Test void astRoundTripKeepsPositionsDiagnosticsAndBytes() throws Exception {
        write("inc/lib.azs", "int f(int a){\n  return a * 2;\n}\n");
        Path main = write("main.azs", "#include \"inc/lib.azs\"\nint main(){\n  return f(21);\n}\n");
        JsonObject tree = tree(main); String text = tree.toString();
        byte[] direct = Compiler.compile(tree).toValue().toAbdFormat();
        assertEquals(text, tree.toString(), "compile must not mutate the readable AST");
        JsonObject restored = JsonParser.parseString(text).getAsJsonObject();
        assertArrayEquals(direct, Compiler.compile(restored).toValue().toAbdFormat());
        // The same semantic error is reported identically from source and from the stored AST.
        write("bad/inc/lib.azs", "int f(int a){\n  string s = a;\n  return a;\n}\n");
        Path bad = write("bad/main.azs", Files.readString(main));
        String fromSource = failure(bad).getMessage();
        JsonObject edited = JsonParser.parseString(text).getAsJsonObject();
        JsonObject body = function(edited, "f");
        JsonObject definition = JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"vardef\",\"param\":[\"s\",{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"a\"],\"_file\":1,\"_line\":2,\"_column\":14}],\"_file\":1,\"_line\":2,\"_column\":3,\"declared-type\":\"string\"}").getAsJsonObject();
        JsonArray script = new JsonArray(); script.add(definition); body.getAsJsonArray("script").forEach(script::add); body.add("script", script);
        assertEquals(fromSource, assertThrows(CompileException.class, () -> Compiler.compile(edited)).getMessage());
        assertTrue(fromSource.startsWith("inc/lib.azs:2:3: error: in function f: "), fromSource);
    }

    @Test void cyclicTreesAndSourceTablesFailWithoutHanging() throws Exception {
        JsonObject tree = tree("int main(){\n  return 1;\n}");
        JsonObject returned = function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject();
        returned.add("self", returned);
        assertEquals("<source>:2:3: error: AST contains a cycle",
                assertThrows(CompileException.class, () -> Compiler.compile(tree)).getMessage());
        JsonObject sources = tree("int main(){return 1;}");
        sources.add("sources", JsonParser.parseString("[{\"name\":\"a.azs\"},{\"name\":\"b.azs\",\"included-from\":{\"source\":1,\"line\":1,\"column\":1}}]"));
        assertEquals("<source>: error: Malformed script JSON at /sources/1/included-from/source: expected an earlier source index, got 1",
                assertThrows(CompileException.class, () -> Compiler.compile(sources)).getMessage());
        // An AST without a sources table is named after the default file, for example the compile-json input.
        JsonObject anonymous = tree("int main(){\n  return missing;\n}");
        anonymous.remove("sources");
        Diagnostics diagnostics = new Diagnostics().defaultFile("tree.json");
        assertEquals("tree.json:2:10: error: in function main: Unknown variable: missing",
                assertThrows(CompileException.class, () -> Compiler.compile(anonymous, diagnostics)).getMessage());
        assertEquals(List.of(new Diagnostic.Position("tree.json", 2, 10)),
                diagnostics.diagnostics().stream().map(Diagnostic::position).toList());
    }

    @Test void postParseErrorsPointAtTheirDeclarations() throws Exception {
        CompileException duplicate = failure("int f():0005{return 1;}\nint g():0005{return 2;}\nint main(){return 0;}\n");
        assertEquals("<source>:2:6: error: Duplicate function ID: 0x0fff0005\n<source>:1:6: note: previous definition is here",
                duplicate.getMessage());
        assertEquals("<source>:2:13: error: Conflicting extern declaration: f\n<source>:1:13: note: previous declaration is here",
                failure("extern int f(int):12340002;\nextern int f(string):12340002;\nint main(){return 0;}\n").getMessage());
        assertEquals("<source>:2:1: error: Assumed namespace overlaps the module namespace\n<source>:1:1: note: module namespace declared here",
                failure("#namespace 1234\n#assume_hint LIB 1234\nint main(){return 0;}\n").getMessage());
        assertEquals("<source>:2:7: error: Explicit function ID is reserved: f",
                failure("int main(){return 0;}\nvoid f():0000{}\n").getMessage());
        assertEquals("<source>:3:7: error: Duplicate class: P\n<source>:1:7: note: previous definition is here",
                failure("class P{int x;}\n\nclass P{int y;}\nvoid main(){}").getMessage());
        // The same header included twice is called out.
        Path header = write("once.azs", "int once() { return 1; }\n");
        Path twice = write("twice.azs", "#include \"once.azs\"\n#include \"once.azs\"\nint main() { return once(); }\n");
        assertEquals("once.azs:1:9: error: Duplicate function definition: once (the header was included twice; add an include guard)\n"
                + "twice.azs:2:1: note: in file included from here\n"
                + "once.azs:1:9: note: previous definition is here\n"
                + "twice.azs:1:1: note: in file included from here", failure(twice).getMessage());
        assertTrue(Files.exists(header));
    }

    @Test void compilerAndCodecIdErrorsPrintHexIds() throws Exception {
        // Duplicate positions in a stored AST are reported at the later definition.
        JsonObject duplicate = tree("int f():0005{return 1;}\nint g():0006{return 2;}\nint main(){return 0;}\n");
        function(duplicate, "g").getAsJsonObject("metadata").addProperty("position", 5);
        assertEquals("<source>:2:6: error: Duplicate function ID: 0x0fff0005",
                assertThrows(CompileException.class, () -> Compiler.compile(duplicate)).getMessage());
        JsonObject conflicting = tree("extern int a(int):12340001;\nextern int b(int):12340002;\nint main(){return a(1)+b(2);}\n");
        JsonObject second = conflicting.getAsJsonObject("extern-signatures").getAsJsonObject("b");
        second.addProperty("id", "12340001"); second.add("param-types", JsonParser.parseString("[\"string\"]"));
        assertEquals("<source>:2:13: error: Conflicting external signature ID: 0x12340001",
                assertThrows(CompileException.class, () -> Compiler.compile(conflicting)).getMessage());
        AcsObject program = Compiler.compile(tree("extern int a(int):12340001;\nint main(){return a(1);}\n"));
        ((AcsObject) ((AcsArray) program.mmp.get("f")).acsa.get(0)).put("id", 0x0abd0001);
        String script = assertThrows(IllegalArgumentException.class, program::toValue).getMessage();
        assertTrue(script.contains("invalid script function id 0x0abd0001"), script);
        program = Compiler.compile(tree("extern int a(int):12340001;\nint main(){return a(1);}\n"));
        ((AcsObject) ((AcsArray) program.mmp.get("extern-signatures")).acsa.get(0)).put("id", 0x0abd0002);
        String external = assertThrows(IllegalArgumentException.class, program::toValue).getMessage();
        assertTrue(external.contains("invalid external function id 0x0abd0002"), external);
    }

    @Test void commandLineDiagnosticsAreIdenticalForSourceAndJson() throws Exception {
        write("good/inc/lib.azs", "// header\nint f(){\n  string s = \"x\";\n  return 1;\n}\n");
        Path good = write("good/main.azs", "#include \"inc/lib.azs\"\nint main(){return f();}\n");
        Path ast = temporary.resolve("good.ast.json");
        // Options may precede the input path.
        String compiled = run("compile", "-o", temporary.resolve("good.abd").toString(), "--ast", ast.toString(), good.toString());
        assertTrue(compiled.startsWith("Compiled "), compiled);
        JsonObject tree = JsonParser.parseString(Files.readString(ast)).getAsJsonObject();
        function(tree, "f").getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").set(1, new JsonPrimitive(1));
        Path badAst = write("bad.ast.json", tree.toString());
        write("bad/inc/lib.azs", "// header\nint f(){\n  string s = 1;\n  return 1;\n}\n");
        Path bad = write("bad/main.azs", Files.readString(good));
        String fromSource = run("compile", bad.toString(), "-o", temporary.resolve("bad.abd").toString());
        assertEquals("inc/lib.azs:3:3: error: in function f: Initializer for s expected string, got int\n"
                + "main.azs:1:1: note: in file included from here\nError: compilation failed: 1 error\n", fromSource.replace("\r\n", "\n"));
        assertEquals(fromSource, run("compile-json", badAst.toString(), "-o", temporary.resolve("bad.abd").toString()));
        assertFalse(Files.exists(temporary.resolve("bad.abd")));
        Path invalid = write("invalid.json", "{\n  \"metadata\": }");
        assertTrue(run("compile-json", invalid.toString()).replace("\r\n", "\n").startsWith("invalid.json:2:15: error: Invalid JSON: Expected value\n"));
        Path deep = write("deep.json", "\n" + "[".repeat(400));
        assertTrue(run("compile-json", deep.toString()).replace("\r\n", "\n").startsWith("deep.json:2:321: error: JSON nesting exceeds 320 levels\n"));
        assertEquals("Error: Unknown option: --unknown\n", run("compile", good.toString(), "--unknown").replace("\r\n", "\n"));
        assertEquals("Error: Missing value for -o\n", run("compile", good.toString(), "-o").replace("\r\n", "\n"));
        assertEquals("Error: Unexpected argument: extra.azs\n", run("compile", good.toString(), "extra.azs").replace("\r\n", "\n"));
    }

    @Test void compileArgumentsAreSharedWithTheLauncher() {
        Main.CompileArguments parsed = Main.parseCompileArguments(List.of("compile", "--exec-json", "x.json", "dir/p.azs", "--ast", "t.json"));
        assertEquals(Path.of("dir/p.azs"), parsed.source());
        assertNull(parsed.output());
        assertEquals(Path.of("dir/p.exec.abd"), parsed.outputOrDefault());
        assertEquals(Path.of("t.json"), parsed.astOutput());
        assertEquals(Path.of("x.json"), parsed.execOutput());
        assertEquals(Path.of("o.abd"), Main.parseCompileArguments(List.of("compile-json", "a.json", "--output", "o.abd")).output());
        assertThrows(IllegalArgumentException.class, () -> Main.parseCompileArguments(List.of("compile")));
        // "--" ends the options, so a source may begin with '-'.
        Main.CompileArguments dashed = Main.parseCompileArguments(List.of("compile", "-o", "o.abd", "--", "-dash.azs"));
        assertEquals(Path.of("-dash.azs"), dashed.source());
        assertEquals(Path.of("o.abd"), dashed.output());
        assertEquals("Unknown option: -dash.azs", assertThrows(IllegalArgumentException.class,
                () -> Main.parseCompileArguments(List.of("compile", "-dash.azs"))).getMessage());
        assertEquals("Unexpected argument: -o", assertThrows(IllegalArgumentException.class,
                () -> Main.parseCompileArguments(List.of("compile", "--", "a.azs", "-o"))).getMessage());
        assertEquals("Missing input path", assertThrows(IllegalArgumentException.class,
                () -> Main.parseCompileArguments(List.of("compile", "--"))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> Main.parseCompileArguments(List.of("pack", "x")));
    }

    private static List<String> errorLines(CompileException error) {
        return error.errors().stream().map(diagnostic -> diagnostic.format().lines().findFirst().orElseThrow()).toList();
    }
    private static void removeFunction(JsonObject tree, String name) {
        tree.getAsJsonObject("abstract").remove(name);
        for (JsonElement namespace : tree.getAsJsonObject("body").asMap().values())
            namespace.getAsJsonObject().entrySet().removeIf(entry -> entry.getValue().getAsJsonObject()
                    .getAsJsonObject("metadata").get("name").getAsString().equals(name));
    }
    /** Runs the CLI on a thread with a small stack, so deep but valid nesting overflows it. */
    private static int runOnSmallStack(StringBuilder output, String... args) throws InterruptedException {
        var bytes = new ByteArrayOutputStream(); var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int[] status = {-1};
        Thread thread = new Thread(null, () -> status[0] = Main.run(args, stream, stream), "small-stack", 16 << 10);
        thread.start(); thread.join();
        output.append(bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        return status[0];
    }

    @Test void independentErrorsInSeveralFunctionsAreReportedTogether() throws Exception {
        write("inc/lib.azs", "// line 1\nint helper(int a) {\n  return a + 1;\n}\nint broken(int a) {\n  string s = a;\n  return 0;\n}\n");
        Path main = write("d3.azs", "// line 1\n#include \"inc/lib.azs\"\nint main() {\n  string q = 5;\n  return 0;\n}\n");
        CompileException error = failure(main);
        assertEquals("inc/lib.azs:6:3: error: in function broken: Initializer for s expected string, got int\n"
                + "d3.azs:2:1: note: in file included from here\n"
                + "d3.azs:4:3: error: in function main: Initializer for q expected string, got int", error.getMessage());
        assertEquals(2, error.errors().size());
        // Lifecycle functions are stored after the module's functions; errors still follow the source order.
        assertEquals(List.of("<source>:2:3: error: in function a: Initializer for s expected string, got int",
                        "<source>:5:25: error: in function __script_onload: Initializer for s expected string, got int",
                        "<source>:8:3: error: in function b: Initializer for s expected string, got int"),
                errorLines(failure("int a(){\n  string s = 1;\n  return 0;\n}\nvoid __script_onload(){ string s = 2; }\nvoid main(){}\nint b(){\n  string s = 3;\n  return 0;\n}\n")));
        // A function with an error does not hide a missing return elsewhere, and later phases wait for earlier ones.
        assertEquals(List.of("<source>:1:16: error: in function f: Unknown variable: missing",
                        "<source>:2:9: error: in function g: non-void function can reach the end of its body without returning a value"),
                errorLines(failure("int f(){return missing;}\nint g(){}\nint main(){return 0;}\n")));
    }

    @Test void parserRecoversAtTheNextStatementMemberAndDeclaration() {
        String source = "int f() {\n  int a = g(1, 2;\n  return 1;\n}\n"
                + "int h() { return 1 +; }\n"
                + "int k() {\n  if (true) return 1; else return +;\n  for (int i = 0 i < 3; i++) { }\n  int z = ;\n  return 2;\n}\n"
                + "int m(int x { return 1; }\n"
                + "class P {\n  int x;\n  int y = ;\n  int get() { return x +; }\n  int z;\n}\n"
                + "int main() { return 0 }\n";
        assertEquals(List.of("<source>:2:17: error: Expected ')', found ';'",
                        "<source>:5:21: error: Expected expression, found ';'",
                        "<source>:7:36: error: Expected expression, found ';'",
                        "<source>:8:18: error: Expected ';' after statement",
                        "<source>:9:11: error: Expected expression, found ';'",
                        "<source>:12:13: error: Expected ')', found '{'",
                        "<source>:15:11: error: Expected expression, found ';'",
                        "<source>:16:25: error: Expected expression, found ';'"),
                errorLines(failure(source)));
        // An unclosed '(' in a declaration does not swallow the next declaration's error.
        assertEquals(List.of("<source>:1:13: error: Expected ')', found '{'", "<source>:2:18: error: Expected expression, found '*'"),
                errorLines(failure("int f(int x { return (1; }\nint g() { return *; }\nint main() { return 0; }\n")));
        // An error after a whole declaration, such as a duplicate definition or a conflicting extern, skips nothing.
        assertEquals(List.of("<source>:2:6: error: Duplicate function definition: f", "<source>:3:32: error: Expected expression, found ';'"),
                errorLines(failure("int f() { return 1; }\nint f() { return 2; }\nint g() { int a = 1; return a +; }\nint main() { return 0; }\n")));
        assertEquals(List.of("<source>:2:13: error: Conflicting extern declaration: h", "<source>:3:26: error: Expected expression, found ';'"),
                errorLines(failure("extern int h(int a):beef0001;\nextern int h(int a):beef0002;\nclass Q { int x; int y = ; }\nint main() { return 0; }\n")));
        assertEquals(List.of("<source>:3:11: error: Duplicate function definition: P::get", "<source>:4:16: error: Expected expression, found '*'"),
                errorLines(failure("class P{int x;}\nint P::get(){return x;}\nint P::get(){return 1;}\nint g(){return *;}\nint main(){return 0;}\n")));
        // The next declaration may fail at its first token, right after the duplicate.
        assertEquals(List.of("<source>:2:6: error: Duplicate function definition: f", "<source>:3:1: error: Expected identifier"),
                errorLines(failure("int f(){return 1;}\nint f(){return 2;}\n5;\nint main(){return 0;}\n")));
        // A failed if statement skips its else branch as well, also after a branch without braces.
        assertEquals(List.of("<source>:3:12: error: Expected expression, found ')'", "<source>:4:14: error: Expected expression, found ')'"),
                errorLines(failure("int main() {\n  int a = 0;\n  if (a == ) a = 1; else a = 2;\n  while (a < ) a = a + 1;\n  return a;\n}\n")));
        assertEquals(List.of("<source>:3:15: error: Expected expression, found ')'", "<source>:4:7: error: Expected expression, found ';'"),
                errorLines(failure("int main() {\n  int a = 0;\n  while (a == ) if (a) a = 1; else a = 2;\n  a = ;\n  return a;\n}\n")));
        // Cascades at the same token are suppressed: one unterminated block, not one per nesting level.
        assertEquals(List.of("<source>:4:1: error: Unterminated block"),
                errorLines(failure("void main() {\n  if (true) {\n    int x = 1;\n")));
        // Nesting limits stop parsing at once.
        CompileException nesting = failure("int f(){return " + "(".repeat(400) + "1;}\nint g(){return *;}\n");
        assertEquals(1, nesting.errors().size());
        assertTrue(nesting.getMessage().contains("Syntax nesting exceeds 320 levels"), nesting.getMessage());
    }

    @Test void lexerAndPreprocessorReportEveryError() throws Exception {
        assertEquals(List.of("<source>:1:13: error: Unexpected character: @", "<source>:1:15: error: Unexpected character: #"),
                errorLines(failure("void main(){@;#;}")));
        // A supplementary or invisible character is one error, printed as its code point.
        assertEquals(List.of("<source>:1:13: error: Unexpected character: U+1F600", "<source>:1:16: error: Unexpected character: U+202E"),
                errorLines(failure("void main(){\uD83D\uDE00;\u202E;}")));
        // A broken string or number is skipped by itself.
        assertEquals(List.of("<source>:2:14: error: Unknown string escape: \\q", "<source>:3:11: error: Invalid numeric exponent",
                        "<source>:4:11: error: Invalid numeric suffix", "<source>:5:14: error: Newline in string literal",
                        "<source>:6:11: error: Unexpected character: @"),
                errorLines(failure("void main(){\n  string a = \"\\q x\" + \"y\";\n  int b = 1e;\n  int c = 2x;\n  string d = \"open\n  int e = @;\n}\n")));
        // A failed #ifdef opens an inactive branch, so its #else and #endif still match.
        write("inc/broken.azs", "#bogus\n");
        Path main = write("pre.azs", "#bogus\n#ifdef\nint broken(\n#else\n#endif\n#include \"nothere.azs\"\n#include \"inc/broken.azs\"\n#define BAD + *\n#undef\nint main(){return 0;}\n");
        assertEquals("pre.azs:1:1: error: Unknown preprocessor directive: #bogus\n"
                + "pre.azs:2:1: error: Missing condition argument\n"
                + "pre.azs:6:1: error: Cannot open include file \"nothere.azs\": file not found\n"
                + "inc/broken.azs:1:1: error: Unknown preprocessor directive: #bogus\n"
                + "pre.azs:7:1: note: in file included from here\n"
                + "pre.azs:9:1: error: Invalid identifier: ", failure(main).getMessage());
        // Preprocessing errors stop before lexing, and lexing errors before parsing.
        assertEquals(List.of("<source>:1:1: error: Unknown preprocessor directive: #bogus"), errorLines(failure("#bogus\nvoid main(){@;}\n")));
        assertEquals(List.of("<source>:1:13: error: Unexpected character: @"), errorLines(failure("void main(){@;}\nint f(){return *;}\n")));
    }

    @Test void classErrorsAreReportedPerClassWithBarriersBetweenPhases() throws Exception {
        JsonObject tree = tree("class A{int x;}\nclass B{int y;}\nvoid main(){}");
        removeFunction(tree, "A::<ctor>"); removeFunction(tree, "B::<ctor>");
        CompileException missing = assertThrows(CompileException.class, () -> Compiler.compile(tree));
        assertEquals(List.of("<source>:1:7: error: in class A: Missing class function: A::<ctor>",
                "<source>:2:7: error: in class B: Missing class function: B::<ctor>"), errorLines(missing));
        assertFalse(missing.internal());
        // A layout error stops before the class functions are checked.
        JsonObject layout = tree("class A{int x;}\nclass B{\n  int y;\n}\nvoid main(){}");
        removeFunction(layout, "A::<ctor>");
        layout.getAsJsonArray("classes").get(1).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("type", "Missing");
        CompileException barrier = assertThrows(CompileException.class, () -> Compiler.compile(layout));
        assertEquals(List.of("<source>:3:3: error: in class B: Unknown type: Missing"), errorLines(barrier));
        // Two invalid classes from source: both reported, and no function body is checked.
        assertEquals(List.of("<source>:1:7: error: in class P: A type parameter bound must be a class value or pointer",
                        "<source>:2:7: error: in class Q: A type parameter bound must be a class value or pointer"),
                errorLines(failure("class P<T extends int>{T x;}\nclass Q<U extends string>{U y;}\nint main(){return missing;}\n")));
        // The parser recovers at the next class member.
        assertEquals(List.of("<source>:1:20: error: Duplicate class member: P.x", "<source>:2:20: error: Duplicate class member: Q.y"),
                errorLines(failure("class P{int x;int x;}\nclass Q{int y;int y;}\nint main(){return missing;}\n")));
    }

    @Test void errorLimitStopsWithANote() throws Exception {
        String statements = "void main(){\n" + "  int a = ;\n".repeat(30) + "}\n";
        CompileException capped = failure(statements);
        assertEquals(Diagnostics.DEFAULT_MAX_ERRORS, capped.errors().size());
        List<Diagnostic> all = capped.diagnostics();
        assertEquals("note: too many errors emitted (limit 20); stopping", all.get(all.size() - 1).format());
        assertTrue(capped.getMessage().endsWith("\nnote: too many errors emitted (limit 20); stopping"), capped.getMessage());
        var script = new GeneraterJson.AzScript();
        assertEquals(List.of("<source>:2:11: error: Expected expression, found ';'"), errorLines(assertThrows(CompileException.class,
                () -> script.execute(statements, new Diagnostics().maxErrors(1)))));
        assertEquals(30, assertThrows(CompileException.class, () -> script.execute(statements, new Diagnostics().maxErrors(0))).errors().size());
        // Exactly as many errors as the limit end the phase without the note.
        CompileException exact = assertThrows(CompileException.class, () -> script.execute(statements, new Diagnostics().maxErrors(30)));
        assertEquals(30, exact.diagnostics().size());
        // Function bodies count against the same limit.
        StringBuilder functions = new StringBuilder();
        for (int i = 0; i < 25; i++) functions.append("int f").append(i).append("(){ string s = ").append(i).append("; return 0; }\n");
        CompileException semantic = failure(functions + "int main(){return 0;}\n");
        assertEquals(20, semantic.errors().size());
        assertTrue(semantic.getMessage().startsWith("<source>:1:11: error: in function f0: "), semantic.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Diagnostics().maxErrors(-1));
        assertFalse(Diagnostics.failFast().recovering());
    }

    @Test void commandLineReportsSeveralErrorsAndStopsAtTheLimit() throws Exception {
        write("inc/c.azs", "int f(){\n  return \"s\";\n}\n");
        write("inc/b.azs", "// b\n#include \"c.azs\"\n");
        Path main = write("a.azs", "#include \"inc/b.azs\"\nint main(){\n  string s = 1;\n  return f();\n}\n");
        String all = run("compile", main.toString(), "-o", temporary.resolve("a.abd").toString()).replace("\r\n", "\n");
        assertEquals("inc/c.azs:2:3: error: in function f: return expected int, got string\n"
                + "inc/b.azs:2:1: note: in file included from here\n"
                + "a.azs:1:1: note: in file included from here\n"
                + "a.azs:3:3: error: in function main: Initializer for s expected string, got int\n"
                + "Error: compilation failed: 2 errors\n", all);
        // --max-errors 1 prints the first error with its whole include chain.
        assertEquals("inc/c.azs:2:3: error: in function f: return expected int, got string\n"
                + "inc/b.azs:2:1: note: in file included from here\n"
                + "a.azs:1:1: note: in file included from here\n"
                + "Error: compilation failed: 1 error\n", run("compile", "--max-errors", "1", main.toString()).replace("\r\n", "\n"));
        assertFalse(Files.exists(temporary.resolve("a.abd")));
        var bytes = new ByteArrayOutputStream(); var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(1, Main.run(new String[]{"compile", main.toString()}, stream, stream));
        Path good = write("good.azs", "int main(){return 0;}\n");
        assertEquals(0, Main.run(new String[]{"compile", good.toString(), "--max-errors", "0", "-o", temporary.resolve("good.abd").toString()}, stream, stream));
        assertEquals(1, Main.run(new String[]{"compile", good.toString(), "--max-errors", "x"}, stream, stream));
        assertEquals("Error: --max-errors expects a non-negative integer, got -2\n", run("compile", good.toString(), "--max-errors", "-2").replace("\r\n", "\n"));
        assertEquals(5, Main.parseCompileArguments(List.of("compile", "x.azs", "--max-errors", "5")).maxErrors());
        assertEquals(Diagnostics.DEFAULT_MAX_ERRORS, Main.parseCompileArguments(List.of("compile", "x.azs")).maxErrors());
        assertTrue(Main.parseCompileArguments(List.of("compile", "--debug-compiler", "x.azs")).flags().contains("--debug-compiler"));
        // Paths the platform cannot represent are usage errors with status 1, not internal errors.
        for (String[] args : List.of(new String[]{"compile", "a\0b.azs"}, new String[]{"compile", good.toString(), "-o", "o\0.abd"},
                new String[]{"compile-json", "t\0.json"}, new String[]{"pack", "out\0.abd", good.toString()})) {
            var output = new ByteArrayOutputStream(); var printer = new PrintStream(output, true, StandardCharsets.UTF_8);
            assertEquals(1, Main.run(args, printer, printer), String.join(" ", args));
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.startsWith("Error: ") && text.contains("Nul character not allowed") && !text.contains("internal compiler error"), text);
        }
        assertThrows(IllegalArgumentException.class, () -> Main.parseCompileArguments(List.of("compile", "a\0b.azs")));
    }

    @Test void sourceAndIncludeFileNamesMustBePrintable() throws Exception {
        // Diagnostics print file names, so a control character in one could forge an output line.
        Path control;
        try { control = write("a\u0001b.azs", "int main(){return 0;}\n"); }
        catch (InvalidPathException | IOException unsupported) {
            Assumptions.abort("The file system cannot name a file with a control character"); return;
        }
        String problem = "a source name cannot contain control or bidirectional formatting characters";
        assertEquals("Invalid source file name: " + problem, assertThrows(IllegalArgumentException.class,
                () -> new GeneraterJson.AzScript().execute(control)).getMessage());
        assertEquals("Error: Invalid source file name: " + problem + "\n", run("compile", control.toString(), "-o", temporary.resolve("c.abd").toString()).replace("\r\n", "\n"));
        Path main = write("names.azs", "#include \"a\u0001b.azs\"\nint main(){return 0;}\n");
        assertEquals("names.azs:1:1: error: Invalid include file name: " + problem, failure(main).getMessage());
    }

    @Test void compileJsonInputErrorsNameTheirJsonPath() throws Exception {
        JsonObject base = tree("int main(){print(\"hi \"+1);return 3;}");
        String main = "/body/fff/_funcfff0000";
        record Case(String name, String expected, java.util.function.Consumer<JsonObject> edit) {}
        List<Case> cases = List.of(
                new Case("pos-str", main + "/metadata/position: expected an integer, got \"abc\"",
                        tree -> function(tree, "main").getAsJsonObject("metadata").addProperty("position", "abc")),
                new Case("param-int", main + "/metadata/param: expected an array, got 5",
                        tree -> function(tree, "main").getAsJsonObject("metadata").addProperty("param", 5)),
                new Case("param-string", main + "/script/0/param: expected an array, got \"x\"",
                        tree -> function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject().addProperty("param", "x")),
                new Case("var-object", main + "/script/1/param/0/t: required field is missing",
                        tree -> function(tree, "main").getAsJsonArray("script").get(1).getAsJsonObject().getAsJsonArray("param")
                                .set(0, JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[{\"a\":1}]}").getAsJsonObject().getAsJsonArray("param").get(0))),
                new Case("no-body", "/body: required field is missing", tree -> tree.remove("body")),
                new Case("line-string", main + "/script/0/_line: expected a positive integer, got \"x\"",
                        tree -> function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject().addProperty("_line", "x")),
                new Case("file-range", main + "/script/0/_file: source index 3 is outside the sources table (1 entry)",
                        tree -> function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject().addProperty("_file", 3)),
                new Case("cyclic-sources", "/sources/1/included-from/source: expected an earlier source index, got 1",
                        tree -> tree.add("sources", JsonParser.parseString("[{\"name\":\"a.azs\"},{\"name\":\"b.azs\",\"included-from\":{\"source\":1,\"line\":1,\"column\":1}}]"))),
                new Case("compound", main + "/script/0/compound: expected true, got false",
                        tree -> function(tree, "main").getAsJsonArray("script").get(0).getAsJsonObject().addProperty("compound", false)),
                new Case("var-name", main + "/script/1/param/0: expected a string, got an object",
                        tree -> function(tree, "main").getAsJsonArray("script").set(1, JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[{\"a\":1}]}"))),
                new Case("member-set", main + "/script/0/param/0: expected an object, got 1",
                        tree -> function(tree, "main").getAsJsonArray("script").set(0, JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"member-set\",\"param\":[1,2]}"))),
                new Case("object-new", main + "/script/0/param: object-new needs a type name",
                        tree -> function(tree, "main").getAsJsonArray("script").set(0, JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"object-new\"}"))),
                new Case("bidi-name", "/sources/0/name: a source name cannot contain control or bidirectional formatting characters",
                        tree -> tree.add("sources", JsonParser.parseString("[{\"name\":\"a" + (char) 0x202e + "b.azs\"}]"))),
                // Keys are printed escaped, so input text cannot forge or reorder a diagnostic line.
                new Case("unsafe-key", "/ext/a\\u000aevil.azs:1:1: error: forged\\u202e: expected a string, got 5",
                        tree -> tree.getAsJsonObject("ext").addProperty("a\nevil.azs:1:1: error: forged" + (char) 0x202e, 5)),
                new Case("unsafe-value", main + "/metadata/position: expected an integer, got \"x\\u2028\\u202e\"",
                        tree -> function(tree, "main").getAsJsonObject("metadata").addProperty("position", "x" + (char) 0x2028 + (char) 0x202e)),
                new Case("class-methods", "/classes/0/methods: required field is missing",
                        tree -> tree.add("classes", JsonParser.parseString("[{\"name\":\"A\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}],\"constructor\":\"A::<ctor>\"}]"))));
        for (Case item : cases) {
            JsonObject tree = base.deepCopy(); item.edit().accept(tree);
            Path json = write(item.name() + ".json", tree.toString());
            String output = run("compile-json", json.toString(), "-o", temporary.resolve(item.name() + ".abd").toString()).replace("\r\n", "\n");
            assertTrue(output.startsWith(item.name() + ".json: error: Malformed script JSON at " + item.expected() + "\n"), output);
            assertTrue(output.endsWith("\nError: compilation failed: " + output.lines().filter(line -> line.contains(": error: ")).count()
                    + (output.lines().filter(line -> line.contains(": error: ")).count() == 1 ? " error\n" : " errors\n")), output);
            assertFalse(output.contains("internal") || output.contains("Character a is neither") || output.contains("Exception"), output);
        }
        // Deliberate compiler errors on forged trees keep their own messages.
        JsonObject missingConstructor = base.deepCopy();
        missingConstructor.add("classes", JsonParser.parseString("[{\"name\":\"A\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}],\"methods\":{},\"constructor\":\"A::<ctor>\"}]"));
        assertEquals("tree.json: error: in class A: Missing class function: A::<ctor>", assertThrows(CompileException.class,
                () -> Compiler.compile(missingConstructor, new Diagnostics().defaultFile("tree.json"))).getMessage());
        JsonObject internalOperation = base.deepCopy();
        function(internalOperation, "main").add("script", JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"#allocate\",\"param\":[\"X\"]}"));
        assertEquals("<source>:1:9: error: in function main: Internal compiler operation is not allowed in source AST", assertThrows(CompileException.class,
                () -> Compiler.compile(internalOperation, new Diagnostics().defaultFile("tree.json"))).getMessage());
        // A class function without its owner class is a deliberate error, not a failure inside the compiler,
        // also when an extern declaration registers the class function.
        JsonObject ownerless = tree("class A{int x;}\nvoid main(){}");
        function(ownerless, "A::<ctor>").getAsJsonObject("metadata").remove("owner-class");
        assertEquals("<source>:1:7: error: in class A: Function owner must be a string", assertThrows(CompileException.class,
                () -> Compiler.compile(ownerless, new Diagnostics().defaultFile("tree.json"))).getMessage());
        JsonObject hinted = tree("#namespace_hint LIB\n#assume_hint LIB abcd\nclass P {\n  int x;\n  extern P(int v):abcd0002;\n}\nP::P(int v):0002 { this.x = v; }\n");
        function(hinted, "P::<ctor>").getAsJsonObject("metadata").remove("owner-class");
        CompileException noOwner = assertThrows(CompileException.class, () -> Compiler.compile(hinted, new Diagnostics().defaultFile("tree.json")));
        assertEquals("<source>:7:5: error: in function P::P: Class function has no owner class: P::<ctor>", noOwner.getMessage());
        assertFalse(noOwner.internal());
        // An extern signature without an ID needs an abstract mapping.
        JsonObject unmapped = tree("extern int h(int a):beef0001;\nint main(){return h(1);}\n");
        unmapped.getAsJsonObject("abstract").remove("h"); unmapped.getAsJsonObject("extern-signatures").getAsJsonObject("h").remove("id");
        assertEquals("<source>:1:13: error: External signature has no abstract mapping: h", assertThrows(CompileException.class,
                () -> Compiler.compile(unmapped, new Diagnostics().defaultFile("tree.json"))).getMessage());
        // A rejected source name never labels an error, also through the library API without a default file.
        JsonObject unsafeName = base.deepCopy();
        unsafeName.add("sources", JsonParser.parseString("[{\"name\":\"evil\\u202etxt.azs\\nfake.azs:1:1: error: injected\"}]"));
        assertEquals("<source>: error: Malformed script JSON at /sources/0/name: a source name cannot contain control or bidirectional formatting characters",
                assertThrows(CompileException.class, () -> Compiler.compile(unsafeName)).getMessage());
        // Numbers beyond Gson's limits fail as malformed input, never as an internal error.
        Path huge = write("huge.json", base.toString().replace("\"hi \",1", "\"hi \",1e20000"));
        var bytes = new ByteArrayOutputStream(); var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(1, Main.run(new String[]{"compile-json", huge.toString(), "-o", temporary.resolve("huge.abd").toString()}, stream, stream));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("huge.json: error: Malformed script JSON at " + main
                + "/script/0/param/0/param/1: number is outside the supported range: 1e20000"), bytes::toString);
    }

    @Test void parsedProgramsAlwaysPassTheShapeCheck() throws Exception {
        // Any expression may be a receiver; the compiler rejects these by type, not as malformed ASTs.
        for (String statement : List.of("int a = \"abc\".x;", "int b = \"abc\"[0];", "\"abc\"[0] = 1;", "\"abc\".x = 2;",
                "return \"abc\".f(1);", "int c = 1(2);", "var e = true[1];", "null[0] = 1;", "var f = {1;}.x;")) {
            var script = new GeneraterJson.AzScript(); Diagnostics diagnostics = new Diagnostics();
            script.execute("int main(){\n  " + statement + "\n  return 0;\n}\n", diagnostics);
            CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(script.toObj(), diagnostics), statement);
            assertFalse(error.internal(), error::getMessage);
            assertTrue(error.getMessage().startsWith("<source>:2:") && error.getMessage().contains("Member access requires a known class type"),
                    error::getMessage);
        }
    }

    @Test void unexpectedExceptionsAreInternalErrorsOrMalformedInput() throws Exception {
        assertTrue(Diagnostics.userError(new IllegalArgumentException("x")));
        assertTrue(Diagnostics.userError(new TreeLimits.LimitExceeded("x", 1, 1)));
        assertTrue(Diagnostics.userError(new CompileException(Diagnostic.error(null, "x"))));
        assertFalse(Diagnostics.userError(new NumberFormatException("x")));
        assertFalse(Diagnostics.userError(new NullPointerException()));
        assertFalse(Diagnostics.userError(new IllegalStateException()));
        assertFalse(Diagnostics.userError(new StackOverflowError()));
        assertFalse(Diagnostics.userError(new CompileException(Diagnostic.internalError(null, null, new NullPointerException(), List.of()))));
        // An unexpected exception at a node: an internal error for a parsed program, malformed input for a supplied AST.
        JsonObject node = JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"x\"],\"_file\":1,\"_line\":3,\"_column\":10}").getAsJsonObject();
        SourceMap sources = SourceMap.fromJson(JsonParser.parseString("[{\"name\":\"main.azs\"},{\"name\":\"inc/lib.azs\",\"included-from\":{\"source\":0,\"line\":2,\"column\":1}}]"), "<source>").input("tree.json");
        NullPointerException cause = new NullPointerException("boom");
        CompileException internal = Compiler.unexpected(cause, node, "function main", new Compiler.CompilationState(new Diagnostics(), sources, true));
        assertEquals("inc/lib.azs:3:10: error: in function main: internal compiler error: NullPointerException: boom\n"
                + "main.azs:2:1: note: in file included from here\n"
                + "note: this is a compiler bug; rerun with --debug-compiler for a stack trace", internal.getMessage());
        assertTrue(internal.internal() && internal.fatal());
        assertSame(cause, internal.getCause());
        CompileException malformed = Compiler.unexpected(cause, node, "function main", new Compiler.CompilationState(new Diagnostics(), sources, false));
        assertEquals("tree.json: error: in function main: Malformed script JSON near inc/lib.azs:3:10", malformed.getMessage());
        assertTrue(malformed.fatal() && !malformed.internal());
        // A stack overflow while compiling a parsed program is an internal compiler error with exit status 2.
        Path deep = write("deep.azs", "int main(){\n  return " + "(".repeat(150) + "1" + ")".repeat(150) + ";\n}\n");
        StringBuilder output = new StringBuilder();
        int status = runOnSmallStack(output, "compile", deep.toString(), "-o", temporary.resolve("deep.abd").toString());
        assertEquals(2, status, output::toString);
        assertTrue(output.toString().matches("(?s)deep\\.azs(:\\d+:\\d+)?: error: internal compiler error: StackOverflowError\n"
                + "note: this is a compiler bug; rerun with --debug-compiler for a stack trace\n"
                + "Error: compilation failed: internal compiler error\n"), output::toString);
        StringBuilder debug = new StringBuilder();
        assertEquals(2, runOnSmallStack(debug, "compile", deep.toString(), "--debug-compiler", "-o", temporary.resolve("deep.abd").toString()));
        assertTrue(debug.toString().contains("\njava.lang.StackOverflowError") && debug.toString().contains("\tat "), debug::toString);
        assertFalse(Files.exists(temporary.resolve("deep.abd")));
    }

    @Test void diagnosticsFormatAndHexIds() {
        assertEquals("0x0fff0002", Diagnostic.hexId(0x0fff0002));
        assertEquals("0xffff0001", Diagnostic.hexId(0xffff0001));
        Diagnostic note = Diagnostic.note(new Diagnostic.Position("a.azs", 1, 1), "in file included from here");
        Diagnostic error = Diagnostic.error(new Diagnostic.Position("b.azs", 4, 7), "function main", "bad", List.of(note));
        assertEquals("b.azs:4:7: error: in function main: bad\na.azs:1:1: note: in file included from here", error.format());
        assertEquals("b.azs: error: whole file", Diagnostic.error(Diagnostic.Position.file("b.azs"), "whole file").format());
        assertEquals("function C::operator[]=", Compiler.contextName("C::<op:index-set>"));
        assertEquals("function C::~C", Compiler.contextName("C::<dtor>"));
        assertEquals("function C::C", Compiler.contextName("C::<scoped>"));
        CompileException failure = new CompileException(error);
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(error.format(), failure.getMessage());
    }
}
