package azertia.script;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Qualified base-method calls {@code Base::m(args)}: parsing, static binding and errors. */
class BaseCallTest {
    private static JsonObject tree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private static byte[] bytes(JsonObject tree) throws Exception { return Compiler.compile(tree).toValue().toAbdFormat(); }
    private static CompileException failure(String source) {
        CompileException error = assertThrows(CompileException.class, () -> bytes(tree(source)), source);
        assertFalse(error.internal(), error::getMessage);
        return error;
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
    private static void collect(JsonElement value, String call, List<JsonObject> found) {
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) collect(child, call, found);
        else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("call") && object.get("call").isJsonPrimitive() && object.get("call").getAsString().equals(call)) found.add(object);
            for (var entry : object.entrySet()) collect(entry.getValue(), call, found);
        }
    }
    private static List<JsonObject> nodes(JsonElement value, String call) {
        List<JsonObject> found = new ArrayList<>(); collect(value, call, found); return found;
    }
    /** The ids of the script functions (not built-ins) called by one compiled function. */
    private static List<Integer> calledIds(JsonObject exec, int id) {
        for (JsonElement function : exec.getAsJsonArray("f")) {
            if (function.getAsJsonObject().get("id").getAsInt() != id) continue;
            List<Integer> ids = new ArrayList<>();
            List<JsonElement> pending = new ArrayList<>(); pending.add(function.getAsJsonObject().get("script"));
            while (!pending.isEmpty()) {
                JsonElement value = pending.remove(pending.size() - 1);
                if (value.isJsonArray()) value.getAsJsonArray().forEach(pending::add);
                else if (value.isJsonObject()) {
                    JsonObject object = value.getAsJsonObject();
                    // Built-ins such as the field read 0x0abd0006 are not script functions.
                    if (object.has("t") && object.get("t").getAsInt() == 1 && object.get("id").getAsInt() >>> 16 != 0x0abd)
                        ids.add(object.get("id").getAsInt());
                    object.entrySet().forEach(entry -> pending.add(entry.getValue()));
                }
            }
            return ids;
        }
        throw new AssertionError("Missing function " + Integer.toHexString(id));
    }
    private static int id(JsonObject tree, String name) {
        return Integer.parseUnsignedInt(tree.getAsJsonObject("abstract").get(name).getAsString(), 16);
    }

    private static final String SHADOWED = """
            class A{int x=1;int value(){return 10+x;}}
            class B:A{int y=2;int value(){return A::value()+100;}}
            class C:B{int z=3;int value(){return B::value()+1000;}int root(){return A::value();}
              void hello(){A::value();for(B::value();false;){}}}
            int main(){return 0;}
            """;

    @Test void parserRecordsQualifiedCalls() throws Exception {
        JsonObject tree = tree("""
                class Box<T>{T v;<U> U echo(U u){return u;}}
                class A:Box<int>{int x;
                  string f(int n){A::f(n);return Box::echo<string>("e");}}
                int main(){return 0;}
                """);
        List<JsonObject> calls = nodes(function(tree, "A::f"), "base-call");
        assertEquals(2, calls.size());
        // A statement starting with "Class::" is an expression, not a declaration.
        JsonObject statement = calls.get(0);
        JsonArray params = statement.getAsJsonArray("param");
        assertEquals(3, params.size());
        assertEquals("A", params.get(0).getAsString()); assertEquals("f", params.get(1).getAsString());
        assertEquals("[\"n\"]", params.get(2).getAsJsonObject().get("param").toString());
        assertEquals(3, statement.get("_line").getAsInt()); assertEquals(19, statement.get("_column").getAsInt());
        assertFalse(statement.has("type-args"));
        JsonObject generic = calls.get(1);
        assertEquals("[\"Box\",\"echo\",\"e\"]", generic.get("param").toString());
        assertEquals("[\"string\"]", generic.get("type-args").toString());
    }

    @Test void standaloneExpressionsParseQualifiedCalls() throws Exception {
        var script = new GeneraterJson.AzScript();
        script.execute("class A<T>{T x;int f(){return 1;}}class B{int y;}int main(){return 0;}");
        JsonObject call = GeneraterJson.getExpression("A::f()", 0, script).generate().getAsJsonObject();
        assertEquals("base-call", call.get("call").getAsString());
        assertEquals("[\"A\",\"f\"]", call.get("param").toString());
        // A class name without "::" still starts a declaration.
        assertEquals("vardef", GeneraterJson.getExpression("B b", 0, script).generate().getAsJsonObject().get("call").getAsString());
        assertEquals("<expression>:1:1: error: Name a base class without type arguments in A::method(...); they come from the inheritance chain",
                assertThrows(CompileException.class, () -> GeneraterJson.getExpression("A<int>::f()", 0, script)).getMessage());
    }

    @Test void qualifiedCallsBindStaticallyToTheNamedClass() throws Exception {
        JsonObject tree = tree(SHADOWED);
        JsonObject exec = Compiler.compile(tree).toJson().getAsJsonObject();
        assertEquals(List.of(id(tree, "A::value")), calledIds(exec, id(tree, "B::value")));
        assertEquals(List.of(id(tree, "B::value")), calledIds(exec, id(tree, "C::value")));
        assertEquals(List.of(id(tree, "A::value")), calledIds(exec, id(tree, "C::root")));
        assertEquals(List.of(id(tree, "B::value"), id(tree, "A::value")).stream().sorted().toList(),
                calledIds(exec, id(tree, "C::hello")).stream().sorted().toList());
        // compile-json of the readable AST gives the same bytes.
        assertArrayEquals(bytes(tree), bytes(JsonParser.parseString(tree.toString()).getAsJsonObject()));
    }

    @Test void inheritedAndFieldShadowedMethodsAreReachable() throws Exception {
        // The method visible in the named class may come from its own base, and a derived field may hide it.
        JsonObject tree = tree("""
                class A{int x=4;int value(){return x;}}
                class B:A{int y;}
                class C:B{int value=9;int sum(){return B::value()+value;}}
                int main(){return 0;}
                """);
        JsonObject exec = Compiler.compile(tree).toJson().getAsJsonObject();
        assertEquals(List.of(id(tree, "A::value")), calledIds(exec, id(tree, "C::sum")));
    }

    @Test void rejectsInvalidQualifiedCalls() {
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case("class A{int x;int f(){return 1;}}\nint main(){return A::f();}",
                        "<source>:2:19: error: in function main: A::f() calls are only available inside class functions"),
                new Case("class A{int x;int f(){return 1;}}\nclass B{int y;int g(){return A::f();}}\nint main(){return 0;}",
                        "<source>:2:30: error: in function B::g: A is not B or one of its base classes"),
                new Case("class A{int x;int f(){return B::g();}}\nclass B:A{int y;int g(){return 1;}}\nint main(){return 0;}",
                        "<source>:1:30: error: in function A::f: B is not A or one of its base classes"),
                new Case("class A{int x;}\nclass B:A{int y;int g(){return A::h();}}\nint main(){return 0;}",
                        "<source>:2:32: error: in function B::g: Unknown method: A.h"),
                new Case("class A{int x;}\nclass B:A{int y;int g(){return A::x();}}\nint main(){return 0;}",
                        "<source>:2:32: error: in function B::g: A.x is a field, not a method"),
                new Case("class A{int x;}\nclass B:A{int y;void g(){A::A();}}\nint main(){return 0;}",
                        "<source>:2:26: error: in function B::g: Constructors cannot be called as A::A(...); use a base initializer"),
                new Case("class A{int x;}\nclass B:A{int y;void g(){B::A();}}\nint main(){return 0;}",
                        "<source>:2:26: error: in function B::g: Constructors cannot be called as B::A(...); use a base initializer"),
                new Case("class A{int x;int operator+(int n){return x+n;}}\nclass B:A{int y;int g(){return A::operator+(1);}}\nint main(){return 0;}",
                        "<source>:2:35: error: Operator methods cannot be called as A::operator...(...); qualified calls name ordinary methods"),
                new Case("class A{int x;int f(){return 1;}}\nclass B:A{int y;int g(){return this.A::f();}}\nint main(){return 0;}",
                        "<source>:2:37: error: Write A::method(...) without a receiver; a qualified call always calls on this"),
                new Case("class A{int x;int f(int n){return n;}}\nclass B:A{int y;int g(){return A::f(\"s\");}}\nint main(){return 0;}",
                        "<source>:2:32: error: in function B::g: A::f argument 1 expected int, got string"),
                new Case("class A<T>{T x;int f(){return 1;}}\nclass B:A<int>{int y;int g(){return A<int>::f();}}\nint main(){return 0;}",
                        "<source>:2:37: error: Name a base class without type arguments in A::method(...); they come from the inheritance chain"),
                new Case("class A<T>{T x;int f(){return 1;}}\nclass B:A<int>{int y;void g(){A<int>::f();}}\nint main(){return 0;}",
                        "<source>:2:31: error: Name a base class without type arguments in A::method(...); they come from the inheritance chain"),
                new Case("class A{int x;}\nclass B:A{int y;int g(){return Q::f();}}\nint main(){return 0;}",
                        "<source>:2:32: error: Q is not a class; Q::method(...) must name the current class or one of its base classes"),
                new Case("class A<T>{T x;void f(){T::f();}}\nint main(){return 0;}",
                        "<source>:1:25: error: T is not a class; T::method(...) must name the current class or one of its base classes"),
                new Case("class A{int x;int f(){return 1;}}\nclass B:A{int y;int g(){return A::f;}}\nint main(){return 0;}",
                        "<source>:2:36: error: Expected '(' after A::f; only qualified method calls are supported"),
                new Case("class A{int x;~A(){}}\nclass B:A{int y;void g(){A::~A();}}\nint main(){return 0;}",
                        "<source>:2:29: error: Destructors cannot be called directly; delete the object or let it expire")))
            assertEquals(item.message(), failure(item.source()).getMessage(), item.source());
    }

    @Test void compileJsonChecksTheBaseCallShape() throws Exception {
        JsonObject tree = tree(SHADOWED);
        assertTrue(rejected(tree, copy -> nodeAt(copy).get("param").getAsJsonArray().set(0, new JsonPrimitive(1)))
                .getMessage().contains("expected a string"));
        assertTrue(rejected(tree, copy -> { JsonArray params = new JsonArray(); params.add("A"); nodeAt(copy).add("param", params); })
                .getMessage().contains("base-call needs a class name and a method name"));
        assertTrue(rejected(tree, copy -> nodeAt(copy).get("param").getAsJsonArray().set(0, new JsonPrimitive("C")))
                .getMessage().contains("C is not B or one of its base classes"));
    }
    /** The first base-call node in B::value. */
    private static JsonObject nodeAt(JsonObject tree) { return nodes(function(tree, "B::value"), "base-call").get(0); }
}
