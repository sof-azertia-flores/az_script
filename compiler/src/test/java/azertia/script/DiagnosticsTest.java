package azertia.script;

import azertia.Main;
import com.google.gson.*;
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
        assertEquals("tree.json: error: Malformed script JSON: required metadata, body or expression field is missing or invalid",
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
        String message = assertThrows(CompileException.class, () -> Compiler.compile(sources)).getMessage();
        assertTrue(message.startsWith("<source>: error: sources[1].included-from"), message);
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
