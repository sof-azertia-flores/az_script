package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class VirtualMethodTest {
    private static JsonObject tree(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source); return parser.toObj();
    }
    private static JsonObject compile(String source) throws Exception {
        JsonObject ast = tree(source), restored = JsonParser.parseString(ast.toString()).getAsJsonObject();
        String original = ast.toString(); JsonObject result = null;
        for (boolean debug : List.of(false, true)) {
            var program = Compiler.compile(ast, debug); byte[] wire = program.toValue().toAbdFormat();
            assertArrayEquals(wire, Compiler.compile(restored, debug).toValue().toAbdFormat(), source);
            assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat(), source);
            assertEquals(original, ast.toString());
            if (!debug) result = program.toJson().getAsJsonObject();
        }
        return result;
    }
    private static CompileException failure(String source) {
        CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(tree(source)), source);
        assertFalse(error.internal(), error::getMessage); return error;
    }
    private static List<JsonObject> nodes(JsonElement value, int opcode) {
        List<JsonObject> result = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) result.addAll(nodes(child, opcode));
        else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("t") && object.has("c") && object.get("t").getAsInt() == 0 && object.get("c").getAsInt() == opcode) result.add(object);
            for (JsonElement child : object.asMap().values()) result.addAll(nodes(child, opcode));
        }
        return result;
    }
    private static JsonObject function(JsonObject program, JsonObject ast, String name) {
        int id = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get(name).getAsString(), 16);
        return program.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == id).findFirst().orElseThrow();
    }
    private static int calls(JsonElement value, int id) {
        int count = 0;
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) count += calls(child, id);
        else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("t") && object.has("id") && object.get("t").getAsInt() == 1 && object.get("id").getAsInt() == id) count++;
            for (JsonElement child : object.asMap().values()) count += calls(child, id);
        }
        return count;
    }

    @Test void parserStoresBareOwnMethodMarkersAndOmitsEmptyArrays() throws Exception {
        JsonObject plain = tree("class Plain{int value;int get(){return value;}}void main(){}").getAsJsonArray("classes").get(0).getAsJsonObject();
        for (String key : List.of("virtual-methods", "override-methods", "final-methods")) assertFalse(plain.has(key));
        String source = "class Base{int value;virtual int get(){return value;}virtual final int fixed(){return 7;}}"
                + "class Child:Base{virtual override final int get(){return value+1;}}void main(){}";
        JsonArray classes = tree(source).getAsJsonArray("classes");
        assertEquals(List.of("get", "fixed"), classes.get(0).getAsJsonObject().getAsJsonArray("virtual-methods").asList().stream().map(JsonElement::getAsString).toList());
        JsonObject child = classes.get(1).getAsJsonObject();
        for (String key : List.of("virtual-methods", "override-methods", "final-methods"))
            assertEquals(List.of("get"), child.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList());
        compile(source);
        compile("class Value{int value;virtual Value copy(){Value result;return result;}}void main(){}");
        compile("class Base{int value;virtual extern int get():0fff0100;}int Base::get():0100{return value;}void main(){}");
        compile("class Base{int value;extern virtual int get():0fff0100;}int Base::get():0100{return value;}void main(){}");
    }

    @Test void pointersImplicitThisFlexibleParametersAndBoundsDispatchVirtually() throws Exception {
        String declaration = "class Base{int value;virtual int get(){return value;}int invoke(){return get();}}"
                + "class Child:Base{override int get(){return value+1;}}";
        String source = declaration + "int main(Base* value){return value.get();}";
        JsonObject ast = tree(source), program = compile(source);
        List<JsonObject> calls = nodes(function(program, ast, "main"), ExecOpcodes.CALL_VIRTUAL);
        assertEquals(1, calls.size()); assertEquals(0, calls.get(0).get("slot").getAsInt());
        assertEquals(0, calls.get(0).get("type").getAsInt()); assertEquals(1, calls.get(0).getAsJsonArray("param").size());
        assertEquals(1, nodes(function(program, ast, "Base::invoke"), ExecOpcodes.CALL_VIRTUAL).size());
        compile(declaration + "int read(Base(*) value){return value.get();}void main(){Base value;print(read(value));}");
        source = declaration + "<T extends Base*>int read(T value){return value.get();}void main(){}";
        ast = tree(source); program = compile(source);
        assertEquals(1, nodes(function(program, ast, "read"), ExecOpcodes.CALL_VIRTUAL).size());
        assertTrue(nodes(program, ExecOpcodes.CHECK_OBJECT).isEmpty(), "dispatch never uses reflection result checks");
    }

    @Test void valuesFinalMethodsAndExplicitBaseCallsBindStatically() throws Exception {
        String source = "class Base{int value;virtual int get(){return value;}virtual final int fixed(){return 7;}}"
                + "class Child:Base{override int get(){return Base::get()+1;}}"
                + "int main(Base* pointer){Base value;return value.get()+pointer.fixed();}";
        JsonObject ast = tree(source), program = compile(source);
        assertTrue(nodes(function(program, ast, "main"), ExecOpcodes.CALL_VIRTUAL).isEmpty());
        JsonObject override = function(program, ast, "Child::get");
        assertTrue(nodes(override, ExecOpcodes.CALL_VIRTUAL).isEmpty());
        int base = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("Base::get").getAsString(), 16);
        assertEquals(1, calls(override, base));
        source = "class Base{int value;virtual int get(){return value;}}class Child:Base{override final int get(){return value;}}"
                + "int main(Child* value){return value.get();}";
        ast = tree(source); program = compile(source);
        assertTrue(nodes(function(program, ast, "main"), ExecOpcodes.CALL_VIRTUAL).isEmpty());
    }

    @Test void builtinNamedMembersAndGenericClassReturnsKeepTheirVirtualBinding() throws Exception {
        String source = "class Base{int value;virtual int getDepth(){return value;}int invoke(){return getDepth();}}"
                + "class Child:Base{override int getDepth(){return value+1;}}int main(Base* value){return value.getDepth();}";
        JsonObject ast = tree(source), program = compile(source);
        assertEquals(1, nodes(function(program, ast, "main"), ExecOpcodes.CALL_VIRTUAL).size());
        assertEquals(1, nodes(function(program, ast, "Base::invoke"), ExecOpcodes.CALL_VIRTUAL).size());
        source = "class Box<T>{T value;virtual T get(){return value;}}"
                + "<U>U read(Box<U>* value){return value.get();}void main(){Box<int>* value=new Box<int>();int result=read<int>(value);delete value;}";
        ast = tree(source); program = compile(source);
        JsonObject read = function(program, ast, "read");
        List<JsonObject> virtual = nodes(read, ExecOpcodes.CALL_VIRTUAL); assertEquals(1, virtual.size());
        assertEquals(6, virtual.get(0).get("type").getAsInt());
        assertFalse(nodes(read, ExecOpcodes.CHECK_TYPE).isEmpty(), "symbolic returns retain their context check");
        compile("class Base<T>{T value;virtual T get(){return value;}}class Child<U>:Base<U>{override U get(){return value;}}void main(){}");
        compile("class Base{int value;virtual Base* self(){return this;}}class Child:Base{override Child* self(){return this;}}void main(){}");
    }

    @Test void implicitVirtualMembersTakePriorityOverSpecialBuiltinLowering() throws Exception {
        record Case(String name, String type, String base, String child, int abi) {}
        for (Case item : List.of(
                new Case("str_length", "string", "\"base\"", "\"child\"", 1),
                new Case("reflect_invoke_function", "int", "1", "2", 0),
                new Case("value_compare", "boolean", "false", "true", 4))) {
            String source = "class Base{int value;virtual " + item.type() + " " + item.name() + "(){return " + item.base() + ";}"
                    + item.type() + " run(){return " + item.name() + "();}}"
                    + "class Child:Base{override " + item.type() + " " + item.name() + "(){return " + item.child() + ";}}"
                    + item.type() + " main(Base* value){return value." + item.name() + "();}";
            JsonObject ast = tree(source), program = compile(source);
            for (String name : List.of("Base::run", "main")) {
                List<JsonObject> virtual = nodes(function(program, ast, name), ExecOpcodes.CALL_VIRTUAL);
                assertEquals(1, virtual.size(), item.name() + " in " + name);
                assertEquals(item.abi(), virtual.get(0).get("type").getAsInt());
                assertEquals(1, virtual.get(0).getAsJsonArray("param").size());
            }
        }
    }

    @Test void constructorsAndDestructorsInstallTheirOwnMethodPhase() throws Exception {
        String source = "class Base{int value;Base(){value=get();}~Base(){value=get();}virtual int get(){return 1;}}"
                + "class Child:Base{Child(){value=get();}~Child(){value=get();}override int get(){return 2;}}void main(){}";
        JsonObject ast = tree(source), program = compile(source);
        for (String owner : List.of("Base", "Child")) for (String phase : List.of("<ctor>", "<dtor>")) {
            JsonObject function = function(program, ast, owner + "::" + phase);
            List<JsonObject> tables = nodes(function, ExecOpcodes.SET_METHODS);
            assertEquals(1, tables.size(), owner + phase);
            JsonArray entries = tables.get(0).getAsJsonArray("entries"); assertEquals(1, entries.size());
            int method = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get(owner + "::get").getAsString(), 16);
            assertEquals(method, entries.get(0).getAsJsonObject().get("id").getAsInt());
            assertEquals(1, nodes(function, ExecOpcodes.CALL_VIRTUAL).size(), owner + phase);
        }
    }

    @Test void emptyBaseMethodTablesClearTheDerivedDispatchPhase() throws Exception {
        String source = "class Base{int value;Base(){}~Base(){}}"
                + "class Derived:Base{Derived(){}~Derived(){}virtual int get(){return value;}}void main(){}";
        JsonObject ast = tree(source), program = compile(source);
        for (String phase : List.of("<ctor>", "<dtor>")) {
            List<JsonObject> base = nodes(function(program, ast, "Base::" + phase), ExecOpcodes.SET_METHODS);
            assertEquals(1, base.size()); assertTrue(base.get(0).getAsJsonArray("entries").isEmpty());
            List<JsonObject> derived = nodes(function(program, ast, "Derived::" + phase), ExecOpcodes.SET_METHODS);
            assertEquals(1, derived.size()); assertEquals(1, derived.get(0).getAsJsonArray("entries").size());
        }
    }

    @Test void baseCompletionFlagIsStoredWithTheVoidReturningConstructorCall() throws Exception {
        String source = "class Leaf{int value;~Leaf(){}}Leaf makeLeaf(){Leaf value;return value;}"
                + "class Base{int value;Base(Leaf(*) leaf){}~Base(){}}"
                + "class Derived:Base{Leaf own;Derived():Base(makeLeaf()){}~Derived(){}}void main(){}";
        JsonObject ast = tree(source), program = compile(source);
        int base = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("Base::<ctor>").getAsString(), 16);
        int make = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("makeLeaf").getAsString(), 16);
        JsonObject constructor = function(program, ast, "Derived::<ctor>");
        List<JsonObject> assignments = nodes(constructor, ExecOpcodes.SET).stream()
                .filter(node -> node.get("val").isJsonObject() && node.getAsJsonObject("val").has("c")
                        && node.getAsJsonObject("val").get("c").getAsInt() == ExecOpcodes.EQUAL
                        && calls(node.get("val"), base) == 1).toList();
        assertEquals(1, assignments.size(), "the base call and completion flag belong to the same statement");
        assertEquals(1, calls(assignments.get(0), make));
        assertEquals(JsonParser.parseString("[255]"), assignments.get(0).getAsJsonObject("val").get("v2"));
        AcsObject compiled = Compiler.compile(ast, true);
        for (AcsObject checked : List.of(compiled, ExecCodec.decode(compiled.toValue()))) {
            Deque<AcsElement> pending = new ArrayDeque<>(); pending.push(checked);
            int voidComparisons = 0;
            while (!pending.isEmpty()) {
                AcsElement value = pending.pop();
                if (value instanceof AcsArray array) pending.addAll(array.acsa);
                else if (value instanceof AcsObject node) {
                    if (node.mmp.containsKey("t") && node.getAsInt("t") == 0 && node.mmp.containsKey("c")
                            && node.getAsInt("c") == ExecOpcodes.EQUAL && calls(node.mmp.get("v1").toJson(), base) == 1) {
                        AcsByteArray sentinel = assertInstanceOf(AcsByteArray.class, node.mmp.get("v2"));
                        assertArrayEquals(new byte[]{(byte) 0xff}, sentinel.getBytes()); voidComparisons++;
                    }
                    pending.addAll(node.mmp.values());
                }
            }
            assertEquals(1, voidComparisons, "VOID stays a byte sentinel through the wire codec");
        }
    }

    @Test void derivedCleanupProtectsOwnedFieldsAndStillFinalizesItsBase() throws Exception {
        String source = "class Leaf{int value;~Leaf(){}}"
                + "class Base{int value;~Base(){}virtual int get(){return value;}}"
                + "class Derived:Base{Leaf first;Leaf second;Derived(){}~Derived(){}}"
                + "class Root{Leaf first;Leaf second;Root(){}}"
                + "class Plain{int value;}class Guarded:Plain{Leaf first;Guarded(){}}void main(){}";
        JsonObject ast = tree(source), program = compile(source);
        int baseDestructor = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("Base::<dtor>").getAsString(), 16);
        JsonObject constructor = function(program, ast, "Derived::<ctor>");
        List<JsonObject> guards = nodes(constructor, ExecOpcodes.CLEANUP).stream()
                .filter(node -> node.get("on-error").getAsBoolean()).toList();
        assertTrue(guards.stream().anyMatch(node -> nodes(node.get("val"), ExecOpcodes.DROP).size() == 2
                        && calls(node.get("val"), baseDestructor) == 1),
                "one outer failure guard releases owned fields and the completed base");
        assertTrue(nodes(function(program, ast, "Root::<ctor>"), ExecOpcodes.CLEANUP).stream()
                .anyMatch(node -> node.get("on-error").getAsBoolean() && nodes(node.get("val"), ExecOpcodes.DROP).size() == 2),
                "a root constructor protects its own owned fields");
        assertTrue(nodes(function(program, ast, "Guarded::<ctor>"), ExecOpcodes.CLEANUP).stream()
                .anyMatch(node -> node.get("on-error").getAsBoolean() && nodes(node.get("val"), ExecOpcodes.DROP).size() == 1),
                "derived owned fields remain guarded when the base has no destructor");
        JsonObject destructor = function(program, ast, "Derived::<dtor>");
        assertEquals(2, nodes(destructor, ExecOpcodes.DROP).size());
        assertEquals(1, calls(destructor, baseDestructor));
        assertTrue(nodes(destructor, ExecOpcodes.CLEANUP).stream()
                .filter(node -> !node.get("on-error").getAsBoolean()).count() >= 2,
                "nested unconditional cleanup keeps later finalizers reachable after an earlier throw");
    }

    @Test void incompatibleOverridesAndMissingMarkersHaveLocatedDiagnostics() {
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case("class C{int x;override int f(){return 0;}}void main(){}", "Method C.f is declared override but does not override a virtual method"),
                new Case("class C{int x;final int f(){return 0;}}void main(){}", "Method C.f declared final must be virtual or override"),
                new Case("class B{int x;virtual int f(){return 0;}}class C:B{int f(){return 1;}}void main(){}", "Method C.f overrides virtual method B.f and must be declared override"),
                new Case("class B{int x;virtual final int f(){return 0;}}class C:B{override int f(){return 1;}}void main(){}", "Method C.f cannot override final method B.f"),
                new Case("class B{int x;virtual int f(int n){return n;}}class C:B{override int f(double n){return 1;}}void main(){}", "Override C.f must have the same parameter types as B.f"),
                new Case("class B{int x;virtual int f(){return 0;}}class C:B{override double f(){return 1.0;}}void main(){}", "Override C.f has incompatible return type: double; expected int"),
                new Case("class B<T>{T x;virtual T f(){return x;}}class C<U>:B<U>{override int f(){return 1;}}void main(){}", "Override C.f has incompatible return type: int; expected U"))) {
            CompileException error = failure(item.source()); assertTrue(error.getMessage().contains(item.message()), error.getMessage());
            assertNotNull(error.errors().get(0).position()); assertTrue(error.errors().get(0).position().line() > 0);
        }
    }

    @Test void parserRejectsInvalidModifierPositionsKindsAndAbstractMethods() {
        for (String source : List.of("virtual int f(){return 0;}void main(){}",
                "class C{int x;int f(){return 0;}}override int C::f(){return 1;}void main(){}")) {
            String message = failure(source).getMessage(); assertTrue(message.contains("inside a class declaration"), message);
        }
        for (String member : List.of("virtual C(){}", "virtual ~C(){}", "virtual int operator+(int n){return n;}", "virtual int field;")) {
            String message = failure("class C{int x;" + member + "}void main(){}").getMessage();
            assertTrue(message.contains("ordinary member methods"), message);
        }
        String generic = failure("class C{int x;virtual <T>T f(T value){return value;}}void main(){}").getMessage();
        assertTrue(generic.contains("own type parameters"), generic);
        String abstractMethod = failure("class C{int x;virtual int f();}void main(){}").getMessage();
        assertTrue(abstractMethod.contains("abstract methods are not supported"), abstractMethod);
        String duplicate = failure("class C{int x;virtual virtual int f(){return 0;}}void main(){}").getMessage();
        assertTrue(duplicate.contains("Duplicate virtual modifier"), duplicate);
        String inheritance = failure("class B{int x;}class C:virtual B{int y;}void main(){}").getMessage();
        assertTrue(inheritance.contains("Only public single inheritance is supported"), inheritance);
        for (String word : List.of("virtual", "override", "final")) assertTrue(SourceRules.RESERVED_WORDS.contains(word));
    }

    @Test void compileJsonValidatesModifierArraysAndHonorsValidOwnMethodMarkers() throws Exception {
        String source = "class C{int x;int f(){return x;}}int main(C* value){return value.f();}";
        JsonObject original = tree(source);
        record Mutation(String message, Consumer<JsonObject> change) {}
        for (Mutation item : List.of(
                new Mutation("expected an array", definition -> definition.addProperty("virtual-methods", "f")),
                new Mutation("expected a string", definition -> {JsonArray values=new JsonArray();values.add(1);definition.add("virtual-methods",values);}),
                new Mutation("duplicate virtual method", definition -> {JsonArray values=new JsonArray();values.add("f");values.add("f");definition.add("virtual-methods",values);}),
                new Mutation("must name an own method", definition -> {JsonArray values=new JsonArray();values.add("C::f");definition.add("virtual-methods",values);}),
                new Mutation("must name an own method", definition -> {JsonArray values=new JsonArray();values.add("<ctor>");definition.add("final-methods",values);}))) {
            JsonObject copy = original.deepCopy(); item.change().accept(copy.getAsJsonArray("classes").get(0).getAsJsonObject());
            CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(copy));
            assertFalse(error.internal(), error::getMessage);
            assertTrue(error.getMessage().contains("Malformed script JSON") && error.getMessage().contains(item.message()), error.getMessage());
        }
        JsonObject marked = original.deepCopy(); JsonArray methods = new JsonArray(); methods.add("f");
        marked.getAsJsonArray("classes").get(0).getAsJsonObject().add("virtual-methods", methods);
        JsonObject program = Compiler.compile(marked).toJson().getAsJsonObject();
        assertEquals(1, nodes(function(program, marked, "main"), ExecOpcodes.CALL_VIRTUAL).size());
    }

    @Test void documentedVirtualExampleCompilesThroughAstDebugAndWirePaths() throws Exception {
        compile(Files.readString(Path.of("examples", "virtual-regressions.azs")));
    }
}
