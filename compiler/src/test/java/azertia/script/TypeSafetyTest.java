package azertia.script;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Source and compile-json share one set of naming, hint, namespace and class layout rules. */
class TypeSafetyTest {
    private static JsonObject tree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private static byte[] bytes(JsonObject tree) throws Exception { return Compiler.compile(tree).toValue().toAbdFormat(); }
    private static CompileException failure(String source) {
        return assertThrows(CompileException.class, () -> bytes(tree(source)), source);
    }
    /** Compiles an edited tree; the error must be a located user error, never an internal one. */
    private static CompileException rejected(JsonObject tree, Consumer<JsonObject> edit) {
        JsonObject copy = tree.deepCopy(); edit.accept(copy);
        CompileException error = assertThrows(CompileException.class, () -> bytes(copy), copy::toString);
        assertFalse(error.internal(), error::getMessage);
        return error;
    }
    private static JsonObject function(JsonObject tree, String name) {
        for (JsonElement namespace : tree.getAsJsonObject("body").asMap().values())
            for (JsonElement value : namespace.getAsJsonObject().asMap().values())
                if (value.getAsJsonObject().getAsJsonObject("metadata").get("name").getAsString().equals(name)) return value.getAsJsonObject();
        throw new AssertionError("Missing function " + name);
    }

    @Test void anyIsReservedEverywhereWithALocation() {
        String reserved = "'any' is a reserved word and cannot be used as a name";
        record Case(String source, String at) {}
        for (Case item : List.of(
                new Case("class any{int x;}\nint main(){return 0;}", "1:7"),
                new Case("class P{int any;}\nint main(){return 0;}", "1:13"),
                new Case("class P{int x; int any(){return 1;}}\nint main(){return 0;}", "1:20"),
                new Case("class B<any>{int x;}\nint main(){return 0;}", "1:8"),
                new Case("<any> int f(){return 1;}\nint main(){return 0;}", "1:1"),
                new Case("int any(){return 1;}\nint main(){return 0;}", "1:5"),
                new Case("extern int any():12340001;\nint main(){return 0;}", "1:12"),
                new Case("int f(int any){return 1;}\nint main(){return 0;}", "1:11"),
                new Case("int f(any){return 1;}\nint main(){return 0;}", "1:7"),
                new Case("int main(){\n  int any=1;\n  return 0;\n}", "2:7"),
                new Case("int main(){\n  var any=1;\n  return 0;\n}", "2:7"),
                new Case("int main(){def(any,1);return 0;}", "1:16"),
                new Case("#gvar any\nint main(){return 0;}", "1:1"),
                new Case("int main(){return 0;}\n#define any 1\n", "2:1")))
            assertEquals("<source>:" + item.at() + ": error: " + reserved, failure(item.source()).getMessage(), item.source());
        assertEquals("<source>:1:19: error: 'any' is a reserved word and cannot be used in an expression",
                failure("int main(){return any;}").getMessage());
        // Type names are still read where a type is expected, and ordinary names are unaffected.
        assertDoesNotThrow(() -> bytes(tree("double f(int a){int x=a;double d=1.0;return d;}\nint main(){int x=1;return x;}")));
        assertDoesNotThrow(() -> bytes(tree("class anyway{int many;}\nint main(){int company=1;return company;}")));
    }

    @Test void compileJsonAppliesTheSameNameRulesWithLocations() throws Exception {
        JsonObject tree = tree("#gvar total\nclass P{\n  int x;\n  int get(){return x;}\n}\nint helper(int v){\n  int w=v;\n  return w;\n}\n"
                + "extern int other(int):12340001;\nint main(){return helper(1);}\n");
        assertDoesNotThrow(() -> bytes(tree));
        record Case(String expected, Consumer<JsonObject> edit) {}
        for (Case item : List.of(
                new Case("<source>:2:7: error: in class any: 'any' is a reserved word and cannot be used as a class name",
                        copy -> copy.getAsJsonArray("classes").get(0).getAsJsonObject().addProperty("name", "any")),
                new Case("<source>:3:3: error: in class P: 'any' is a reserved word and cannot be used as a field name",
                        copy -> copy.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("name", "any")),
                new Case("<source>:3:3: error: in class P: Invalid field name: x\\u202e",
                        copy -> copy.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("name", "x\u202e")),
                new Case("<source>:6:11: error: in function any: 'any' is a reserved word and cannot be used as a function name",
                        copy -> function(copy, "helper").getAsJsonObject("metadata").addProperty("name", "any")),
                new Case("<source>:6:11: error: in function helper: 'any' is a reserved word and cannot be used as a parameter name",
                        copy -> function(copy, "helper").getAsJsonObject("metadata").getAsJsonArray("param").set(0, new JsonPrimitive("any"))),
                new Case("<source>:6:11: error: in function helper: Invalid parameter name: v\\u200bw",
                        copy -> function(copy, "helper").getAsJsonObject("metadata").getAsJsonArray("param").set(0, new JsonPrimitive("v\u200bw"))),
                new Case("<source>:7:3: error: in function helper: 'any' is a reserved word and cannot be used as a variable name",
                        copy -> function(copy, "helper").getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").set(0, new JsonPrimitive("any"))),
                new Case("<source>:10:17: error: 'any' is a reserved word and cannot be used as a function name",
                        copy -> { JsonObject externs = copy.getAsJsonObject("extern-signatures"); externs.add("any", externs.remove("other")); }),
                new Case("<source>: error: in global any: 'any' is a reserved word and cannot be used as a global name",
                        copy -> copy.getAsJsonArray("global-variable").set(0, new JsonPrimitive("any"))),
                // A rejected name also appears in the context, which must not reorder or hide the line either.
                new Case("<source>:2:7: error: in class P\\u202eQ: Invalid class name: P\\u202eQ",
                        copy -> copy.getAsJsonArray("classes").get(0).getAsJsonObject().addProperty("name", "P\u202eQ")),
                new Case("<source>:6:11: error: in function he\\u202elper: Invalid function name: he\\u202elper",
                        copy -> function(copy, "helper").getAsJsonObject("metadata").addProperty("name", "he\u202elper")),
                new Case("<source>: error: in global a\\u202eb: Invalid global name: a\\u202eb",
                        copy -> copy.getAsJsonArray("global-variable").set(0, new JsonPrimitive("a\u202eb"))),
                // A supplementary format character, the tag U+E0061, is escaped as its surrogate pair.
                new Case("<source>: error: in global a\\udb40\\udc61b: Invalid global name: a\\udb40\\udc61b",
                        copy -> copy.getAsJsonArray("global-variable").set(0, new JsonPrimitive("a\udb40\udc61b"))),
                // A qualified name is not a source name, but the message quoting it is escaped as well.
                new Case("<source>:6:11: error: in function a\\u202eb::get: Unregistered class function: a\\u202eb::get",
                        copy -> {
                            function(copy, "helper").getAsJsonObject("metadata").addProperty("name", "a\u202eb::get");
                            JsonObject ids = copy.getAsJsonObject("abstract"); ids.add("a\u202eb::get", ids.remove("helper"));
                        }))) {
            String message = rejected(tree, item.edit()).getMessage();
            assertEquals(item.expected(), message);
            assertTrue(message.codePoints().noneMatch(c -> Character.getType(c) == Character.FORMAT), message);
        }
    }

    @Test void identifiersCannotContainFormatOrIgnorableCharacters() {
        // U+202E would reorder the rest of the line in an editor; U+200B is invisible.
        assertEquals("<source>:1:17: error: Unexpected character: U+202E", failure("int main(){int a\u202eb=1;return 0;}").getMessage());
        assertEquals("<source>:1:17: error: Unexpected character: U+200B", failure("int main(){int a\u200bb=1;return 0;}").getMessage());
        assertEquals("<source>:1:1: error: Invalid identifier: a\\u200bb", failure("#gvar a\u200bb\nint main(){return 0;}").getMessage());
        assertEquals("<source>:1:1: error: Invalid identifier: a\\u202e", failure("#define a\u202e 1\nint main(){return 0;}").getMessage());
        // Only a leading byte order mark is skipped; elsewhere U+FEFF must not split int\ufeffx into int x.
        assertEquals("<source>:1:15: error: Unexpected character: U+FEFF", failure("int main(){int\ufeffx=5;return x;}").getMessage());
        assertEquals("<source>:1:18: error: Unexpected character: U+FEFF", failure("int main(){return\ufeff0;}").getMessage());
        assertEquals("<source>:1:17: error: Unexpected character: U+FEFF", failure("int main(){int a\ufeffb=1;return 0;}").getMessage());
        assertDoesNotThrow(() -> bytes(tree("\ufeffint main(){return 0;}")));
        for (char c : new char[]{'\u0000', '\u0001', '\u007f', '\u00ad', '\u200b', '\u200d', '\u2060', '\u202e', '\ufeff'}) {
            assertFalse(SourceRules.isIdentifier("a" + c), () -> "U+" + Integer.toHexString(c));
            assertFalse(SourceRules.identifierPart(c), () -> "U+" + Integer.toHexString(c));
        }
        for (String name : List.of("a", "_", "$x", "x1", "été", "Αβ"))
            assertTrue(SourceRules.isIdentifier(name), name);
        assertFalse(SourceRules.isIdentifier("1a"));
        assertFalse(SourceRules.isIdentifier(""));
    }

    @Test void classLayoutErrorsAreLocatedAndIdenticalForSourceAndCompileJson() throws Exception {
        record Case(String source, String expected) {}
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 70; i++) chain.append("class C").append(i).append("{C").append(i + 1).append(" c;}\n");
        String deep = chain.append("class C70{int x;}\nint main(){return 0;}").toString();
        for (Case item : List.of(
                new Case("class A : B{int x;}\nclass B : A{int y;}\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Inheritance cycle involving A\n<source>:2:7: error: in class B: Inheritance cycle involving B"),
                new Case("int main(){return 0;}\nclass A{}\n", "<source>:2:7: error: in class A: Class A must declare at least one field"),
                new Case("class A{A a;}\nint main(){return 0;}", "<source>:1:7: error: in class A: Class A contains itself by value; use a pointer field"),
                // One containment cycle is one error, at the first class on it.
                new Case("class A{B b;}\nclass B{A a;}\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Class A contains itself by value; use a pointer field"),
                new Case("class A : B{int x;}\nclass B{A a;}\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Class A contains itself by value; use a pointer field"),
                // The parser checked raw class names only, so generic value fields escaped it.
                new Case("class A<T>{B<T> b;}\nclass B<T>{A<T> a;}\nint main(){A<int>* p=null;return 0;}",
                        "<source>:1:7: error: in class A: Class A contains itself by value; use a pointer field"),
                new Case(deep, "<source>:1:7: error: in class C0: Literal object nesting exceeds 64 levels"),
                // Duplicate externs are compared only once every layout is valid, so a broken class reports its own error.
                new Case("class A : Z{int v;}\nclass Z : A{int w;}\nclass C{int x;}\nextern int f(A* a):12340001;\nextern int f(C* c):12340001;\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Inheritance cycle involving A\n<source>:2:7: error: in class Z: Inheritance cycle involving Z"),
                new Case("class A{}\nclass C{int x;}\nextern int f(A* a):12340001;\nextern int f(C* c):12340001;\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Class A must declare at least one field"))) {
            // The parser leaves layouts to the compiler, so the AST exists and compile-json reports the same errors.
            JsonObject tree = tree(item.source());
            assertEquals(item.expected(), assertThrows(CompileException.class, () -> bytes(tree), item.source()).getMessage());
            assertEquals(item.expected(), failure(item.source()).getMessage());
        }
        // The parser still checks base initializers, and reports each one.
        assertEquals("<source>:3:21: error: Constructor A can initialize only its direct base class\n"
                        + "<source>:4:17: error: Constructor D can initialize only its direct base class",
                failure("class B{int x;}\nclass C{int z;}\nclass A : B{int y; A() : C() {}}\nclass D{int w; D() : B() {}}\nint main(){return 0;}").getMessage());
    }

    @Test void hexadecimalIdsAndReservedNamespacesMatchBetweenSourceAndAst() throws Exception {
        // Source accepts a 0x prefix and leading zeros; the AST always holds the canonical spelling.
        JsonObject tree = tree("#namespace 0x0005151\nint f():00000000abcd {return 1;}\nextern int g():00000000abcd;\nextern int h():0X00051510002;\n"
                + "int main(){return f()+g()+h();}\n");
        assertEquals(0x5151, tree.getAsJsonObject("metadata").get("function-namespace").getAsInt());
        assertTrue(tree.getAsJsonObject("body").getAsJsonObject("5151").has("_func5151abcd"), tree::toString);
        assertEquals("abcd", tree.getAsJsonObject("extern-signatures").getAsJsonObject("g").get("id").getAsString());
        assertEquals("51510002", tree.getAsJsonObject("extern-signatures").getAsJsonObject("h").get("id").getAsString());
        byte[] compiled = bytes(tree);
        assertArrayEquals(compiled, bytes(JsonParser.parseString(tree.toString()).getAsJsonObject()));
        // compile-json is strict: only the canonical width, no prefix.
        assertEquals("<source>: error: Invalid namespace: 0x5151", rejected(tree, copy -> {
            JsonObject body = copy.getAsJsonObject("body"); body.add("0x5151", body.remove("5151"));
        }).getMessage());
        assertEquals("<source>:3:13: error: Invalid external ID: 0x0000abcd",
                rejected(tree, copy -> copy.getAsJsonObject("extern-signatures").getAsJsonObject("g").addProperty("id", "0x0000abcd")).getMessage());
        assertEquals("<source>:3:13: error: Invalid external ID: 00000000abcd",
                rejected(tree, copy -> copy.getAsJsonObject("extern-signatures").getAsJsonObject("g").addProperty("id", "00000000abcd")).getMessage());
        for (String invalid : List.of("0x", "0x10000", "g", "-1", "1 2"))
            assertTrue(failure("#namespace " + invalid + "\nint main(){return 0;}").getMessage().contains(
                    invalid.equals("1 2") ? "Expected one hexadecimal namespace" : "Invalid namespace: " + invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> SourceRules.hex("０", 4, "namespace"));
        assertEquals(0xabcd, SourceRules.sourceHex("0x0000000000abcd", 4, "function ID"));

        // One reason text names a reserved namespace in source and in compile-json.
        String runtime = "0abd is reserved for runtime built-ins", lifecycle = "0000 is reserved for lifecycle hooks and hint-library placeholders";
        assertEquals("<source>:1:1: error: Namespace " + runtime, failure("#namespace 0x000000abd\nint main(){return 0;}").getMessage());
        assertEquals("<source>:1:1: error: Namespace " + lifecycle, failure("#namespace 0x00000\nint main(){return 0;}").getMessage());
        JsonObject plain = tree("int main(){return 0;}\n");
        assertEquals("<source>: error: Invalid function namespace: " + runtime,
                rejected(plain, copy -> copy.getAsJsonObject("metadata").addProperty("function-namespace", 0xabd)).getMessage());
        assertEquals("<source>: error: Invalid function namespace: " + lifecycle,
                rejected(plain, copy -> copy.getAsJsonObject("metadata").addProperty("function-namespace", 0)).getMessage());
        // Externs cannot import a runtime built-in, a lifecycle hook or main, however the ID is spelled.
        assertEquals("<source>:1:32: error: Reserved external function ID", failure("extern int g():000000000abd0001;\nint main(){return 0;}").getMessage());
        JsonObject external = tree("extern int g():12340001;\nint main(){return g();}\n");
        for (String id : List.of("abd0001", "0", "1", "fff0000"))
            assertEquals("<source>:1:13: error: Reserved external function: g", rejected(external,
                    copy -> copy.getAsJsonObject("extern-signatures").getAsJsonObject("g").addProperty("id", id)).getMessage(), id);
        // #assume_hint and an AST assumption reject the same namespaces.
        for (int namespace : new int[]{0, 0xabd, 0xfff, 0x10000}) {
            assertFalse(SourceRules.assumableNamespace(namespace));
            if (namespace <= 0xffff)
                assertEquals("<source>:1:1: error: Reserved assumed namespace",
                        failure("#assume_hint L " + Integer.toHexString(namespace) + "\nint main(){return 0;}").getMessage());
        }
        JsonObject assumed = tree("#assume_hint L 1234\nint main(){return 0;}\n");
        assertDoesNotThrow(() -> bytes(assumed));
        assertEquals("<source>: error: Reserved or invalid assumed namespace", rejected(assumed,
                copy -> copy.getAsJsonArray("assume-hints").get(0).getAsJsonObject().addProperty("namespace", 0xfff)).getMessage());
    }

    @Test void hintNamesFollowTheCasesTheCppLoaderChecks() throws Exception {
        // interpreter/runtime_tests.cpp reads the same file, so the Java and C++ hint rules cannot drift.
        List<String> lines = Files.readAllLines(Path.of("../tests/hint_names.txt"), StandardCharsets.UTF_8);
        JsonObject library = tree("#namespace_hint LIB\nint value(){return 1;}\n");
        assertDoesNotThrow(() -> bytes(library));
        int cases = 0;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int space = line.indexOf(' ');
            String verdict = line.substring(0, space), text = line.substring(space + 1);
            assertTrue(verdict.equals("valid") || verdict.equals("invalid"), line);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            if (!text.equals("(empty)")) for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '\\') { decoded.write(text.charAt(i)); continue; }
                assertEquals('x', text.charAt(i + 1), line);
                decoded.write(Integer.parseInt(text.substring(i + 2, i + 4), 16)); i += 3;
            }
            String name = decoded.toString(StandardCharsets.UTF_8); boolean valid = verdict.equals("valid");
            assertEquals(valid, SourceRules.isHint(name), line);
            // compile-json checks a module hint with the same rule.
            if (!name.isEmpty()) {
                JsonObject copy = library.deepCopy(); copy.addProperty("namespace-hint", name);
                if (valid) assertDoesNotThrow(() -> bytes(copy), line);
                else assertTrue(assertThrows(CompileException.class, () -> bytes(copy), line).getMessage().contains("Invalid hint name: "), line);
            }
            cases++;
        }
        assertTrue(cases >= 250, "hint-name cases: " + cases);
        // Directive arguments are checked by the same rule.
        assertEquals("<source>:1:1: error: Invalid hint name: a-b", failure("#namespace_hint a-b\nint main(){return 0;}").getMessage());
        assertEquals("<source>:1:1: error: Invalid hint name: \u00e9", failure("#assume_hint é 1234\nint main(){return 0;}").getMessage());
    }
}
