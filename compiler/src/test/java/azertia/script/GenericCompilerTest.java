package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenericCompilerTest {
    /** Parses and compiles with one collector, so a compiler bug fails the test instead of passing as a rejection. */
    private JsonObject compile(String source) throws Exception {
        var diagnostics = new Diagnostics(); var parser = new GeneraterJson.AzScript();
        JsonObject tree; AcsObject first;
        try { parser.execute(source, diagnostics); tree = parser.toObj(); first = Compiler.compile(tree, diagnostics); }
        catch (CompileException error) { assertFalse(error.internal(), error::getMessage); throw error; }
        AcsObject again = Compiler.compile(JsonParser.parseString(tree.toString()).getAsJsonObject());
        assertArrayEquals(first.toValue().getData(), again.toValue().getData());
        return first.toJson().getAsJsonObject();
    }
    private List<JsonObject> instructions(JsonElement value, int opcode) {
        List<JsonObject> result = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) result.addAll(instructions(child, opcode));
        else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("t") && object.get("t").isJsonPrimitive() && object.get("t").getAsInt() == 0
                    && object.has("c") && object.get("c").getAsInt() == opcode) result.add(object);
            for (JsonElement child : object.asMap().values()) result.addAll(instructions(child, opcode));
        }
        return result;
    }
    private void rejects(String source) {
        assertThrows(IllegalArgumentException.class, () -> compile(source));
    }

    @Test void applicationsShareBodiesAndKeepExactSlots() throws Exception {
        String declarations = "class Box<T>{T value;T get(){return value;}} <U>U id(U value){return value;}";
        JsonObject one = compile(declarations + "void main(){Box<int> a;int n=id(3);}");
        JsonObject many = compile(declarations + "void main(){Box<int>a;Box<string>b;Box<Box<int>>c;int n=id(3);string s=id(\"x\");}");
        assertEquals(one.getAsJsonArray("f").size(), many.getAsJsonArray("f").size());
        for (JsonObject allocation : instructions(many, ExecOpcodes.NEW_BLOCK)) assertEquals(1, allocation.get("size").getAsInt());
        assertFalse(many.toString().contains("Box"), "class names and layouts stay in the compiler");
        assertEquals(2, instructions(many, ExecOpcodes.RETURN_TYPED).size());
        Set<Integer> ids = new HashSet<>();
        for (JsonElement item : many.getAsJsonArray("f")) assertTrue(ids.add(item.getAsJsonObject().get("id").getAsInt()));
    }

    @Test void functionsInferNestedArgumentsAndForwardBounds() throws Exception {
        compile("class Guard<T extends Base<int>*>{T value;}class Base<U>{U value;}"
                + "<T>T unwrap(Base<T> value){return value.value;}"
                + "void main(){Base<int>b;int n=unwrap(b);Guard<Base<int>*>g;}");
        rejects("<T>T choose(T a,T b){return a;}void main(){var x=choose(1,\"x\");}");
        rejects("<T>T make(){T a=make<T>();return a;}void main(){int n=make();}");
    }

    @Test void genericApplicationsRemainInvariantIncludingPhantomArguments() {
        rejects("class Tag<T>{int value;}void main(){Tag<int>a;Tag<string>b=a;}");
        rejects("class A<T>{T value;}class B<T>{T value;}void main(){A<int>a;B<int>b=a;}");
        rejects("class B{int x;}class D:B{int y;}class Box<T>{T value;}"
                + "void main(){Box<D*>a;Box<B*>b=a;}");
    }

    @Test void boundsExposeMembersWithoutPermittingValueSlicing() throws Exception {
        compile("class Base{int x;}class Child:Base{int y;}"
                + "<T extends Base*>int read(T p){return p.x;}"
                + "<T extends Base>int readValue(T p){return p.x;}"
                + "void main(){Child c;int n=readValue(c);Child*p=new Child();n=read(p);delete p;}");
        rejects("class Base{int x;}<T extends Base>Base sliced(T value){return value;}void main(){}");
        rejects("<T>int read(T value){return value.x;}void main(){}");
        rejects("class Base{int x;}<T extends Base*>T invalid(Base* value){return value;}void main(){}");
    }

    @Test void externDefinitionsMatchByTypeParameterPosition() throws Exception {
        compile("#namespace 1234\nextern <U>U identity(U value):12340002;\n"
                + "<T>T identity(T value):0002{return value;}void main(){int n=identity(1);}");
        rejects("#namespace 1234\nextern <U>U f(U value):12340002;\n"
                + "<T>int f(T value):0002{return 1;}void main(){}");
        rejects("class B{int x;}extern <U extends B*>U f(U x):12340002;"
                + "<T>T f(T x){return x;}void main(){}");
    }

    @Test void missingDefaultConstructorDoesNotRejectPureForwarding() throws Exception {
        String declaration = "class P{int x;P(int n){x=n;}}<T>T identity(T value){return value;}";
        compile(declaration + "void main(){P p(3);P q=identity(p);}");
        rejects(declaration + "class Box<T>{T value;}void main(){Box<P>b;}");
    }

    @Test void incompleteOrUnsupportedTypeParameterOperationsAreRejected() {
        for (String body : List.of("T local;", "T* p=null;", "T(*) p;", "var x=value+1;", "var x=value==value;", "delete value;", "T();", "new T();"))
            rejects("<T>void f(T value){" + body + "}void main(){}");
        rejects("class Box<T>{T value;}void main(){Box<void>b;}");
        rejects("class Box<T>{T value;}void main(){Box<any>b;}");
        rejects("class Box<T>{T value;}void main(){Box b;}");
        rejects("class Box<T extends Box<T>>{T value;}void main(){}");
    }

    @Test void reflectionUsesDynamicAbiForSymbolicReturns() throws Exception {
        JsonObject program = compile("<T>T invoke(int id){return reflect_invoke_function<T>(id);}"
                + "void main(){reflect_invoke_function<void>(2);}");
        assertEquals(1, instructions(program, ExecOpcodes.CONTEXT_ABI).size());
        assertEquals(1, instructions(program, ExecOpcodes.RETURN_TYPED).size());
        assertEquals(1, instructions(program, ExecOpcodes.CHECK_OBJECT).size());
        rejects("void main(){reflect_invoke_function<int>(null);}");
        rejects("void main(){reflect_invoke_function(2);}");
        rejects("void main(){reflect_invoke_function<int,string>(2);}");
    }

    @Test void genericMethodsAndInheritedContextsKeepOrdinaryParameterCounts() throws Exception {
        JsonObject program = compile("class Base<T>{T value;T get(){return value;}}"
                + "class Child<A,B>:Base<B>{A other;<U>U echo(U value){return value;}}"
                + "void main(){Child<int,string>c;string s=c.get();int n=c.echo(3);}");
        boolean method = false;
        for (JsonElement item : program.getAsJsonArray("f")) {
            JsonObject function = item.getAsJsonObject();
            if (function.get("hidden-count").getAsInt() == 3) {
                assertEquals(2, function.get("param-count").getAsInt());
                assertEquals(0, function.get("entry-kind").getAsInt()); method = true;
            }
        }
        assertTrue(method);
    }

    private void reflectionRoundTrip(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source);
        JsonObject ast = parser.toObj(), restored = JsonParser.parseString(ast.toString()).getAsJsonObject();
        String original = ast.toString();
        for (boolean debug : List.of(false, true)) {
            byte[] encoded = Compiler.compile(ast, debug).toValue().toAbdFormat();
            assertArrayEquals(encoded, Compiler.compile(restored, debug).toValue().toAbdFormat(), source);
            assertArrayEquals(encoded, ExecCodec.decode(encoded).toValue().toAbdFormat(), source);
            assertEquals(original, ast.toString());
        }
    }

    @Test void reflectionChecksOnlyReferenceAndSymbolicResults() throws Exception {
        String declaration = "class P{int i;string s;float f;double d;boolean b;address a;P* next;buffer<int> values;}";
        record Case(String type, int kind, List<Integer> slots) {}
        for (Case item : List.of(
                new Case("P*", 2, List.of(0, 1, 2, 3, 4, 7, 7, 8)),
                new Case("P", 3, List.of(0, 1, 2, 3, 4, 7, 7, 8)),
                new Case("buffer<int>", 4, List.of()))) {
            String source = declaration + item.type() + " main(){return reflect_invoke_function<" + item.type() + ">(2);}";
            List<JsonObject> checks = instructions(compile(source), ExecOpcodes.CHECK_OBJECT);
            assertEquals(1, checks.size(), source);
            JsonObject checked = checks.get(0), context = checked.getAsJsonObject("context"), call = checked.getAsJsonObject("v");
            assertEquals(item.kind(), context.get("kind").getAsInt(), source);
            assertEquals(item.slots(), checked.getAsJsonArray("slot-types").asList().stream().map(JsonElement::getAsInt).toList());
            assertEquals(item.kind() == 3 ? 8 : 1, context.get("width").getAsInt(), source);
            assertEquals(1, call.get("t").getAsInt());
            assertEquals(0x0abd0008, call.get("id").getAsInt());
            reflectionRoundTrip(source);
        }
        for (String type : List.of("int", "float", "double", "boolean", "string", "address", "void")) {
            String source = type.equals("void") ? "void main(){reflect_invoke_function<void>(2);}"
                    : type + " main(){return reflect_invoke_function<" + type + ">(2);}";
            assertTrue(instructions(compile(source), ExecOpcodes.CHECK_OBJECT).isEmpty(), source);
            reflectionRoundTrip(source);
        }
        JsonObject construction = compile("class P{int x;int read(){return x;}}void main(){P value;P* pointer=new P();print(value.read());delete pointer;}");
        assertTrue(instructions(construction, ExecOpcodes.CHECK_OBJECT).isEmpty());
    }

    @Test void symbolicReflectionUsesOneSharedCheckAndUncheckedSymbolicFieldTags() throws Exception {
        String source = "class P{int x;}<T>T invoke(int id){return reflect_invoke_function<T>(id);}"
                + "void main(){int number=invoke<int>(2);P value=invoke<P>(2);P* pointer=invoke<P*>(2);delete pointer;}";
        List<JsonObject> checks = instructions(compile(source), ExecOpcodes.CHECK_OBJECT);
        assertEquals(1, checks.size(), "generic applications share the reflection body");
        assertEquals(0, checks.get(0).getAsJsonObject("context").get("ref").getAsInt());
        assertTrue(checks.get(0).getAsJsonArray("slot-types").isEmpty());
        reflectionRoundTrip(source);
        source = "class P{int x;}<T extends P*>T invoke(int id){return reflect_invoke_function<T>(id);}"
                + "void main(){P* pointer=invoke<P*>(2);delete pointer;}";
        checks = instructions(compile(source), ExecOpcodes.CHECK_OBJECT);
        assertEquals(1, checks.size());
        assertEquals(0, checks.get(0).getAsJsonObject("context").get("ref").getAsInt());
        assertTrue(checks.get(0).getAsJsonArray("slot-types").isEmpty());
        reflectionRoundTrip(source);
        source = "class Box<T>{T value;}<U>Box<U> invoke(int id){return reflect_invoke_function<Box<U>>(id);}void main(){}";
        checks = instructions(compile(source), ExecOpcodes.CHECK_OBJECT);
        assertEquals(1, checks.size());
        assertEquals(List.of(6), checks.get(0).getAsJsonArray("slot-types").asList().stream().map(JsonElement::getAsInt).toList());
        reflectionRoundTrip(source);
    }

    private static AcsObject fixedContext(int abi, int kind, int width) {
        AcsObject context = new AcsObject(); context.put("abi", abi); context.put("kind", kind); context.put("width", width); return context;
    }
    private AcsObject checkedProgram(AcsObject context, int... slots) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute("void main(){}");
        AcsObject program = Compiler.compile(parser.toObj());
        AcsObject function = (AcsObject) program.getAsAcsArray("f").acsa.get(0); function.put("hidden-count", 1);
        AcsObject checked = new AcsObject(); checked.put("t", 0); checked.put("c", ExecOpcodes.CHECK_OBJECT);
        checked.put("v", new AcsAddress(0L)); checked.put("context", context);
        AcsArray types = new AcsArray(); for (int type : slots) types.acsa.add(new AcsIntegerElement(type)); checked.put("slot-types", types);
        AcsArray body = new AcsArray(); body.acsa.add(checked); function.put("script", body); return program;
    }
    private static AcsObject checkedView(AcsObject program) {
        return (AcsObject) ((AcsObject) program.getAsAcsArray("f").acsa.get(0)).getAsAcsArray("script").acsa.get(0);
    }
    private static AbdValue raw(List<AbdValue> values) {
        AbdSimpleStack stack = new AbdSimpleStack(); stack.values.addAll(values); return stack.toAbd();
    }
    private static AbdValue replaceBody(AbdValue wire, AbdValue body) {
        var root = wire.getAsAss(); var functions = root.values.get(7).getAsAss(); var function = functions.values.get(0).getAsAss();
        function.values.set(5, body); functions.values.set(0, function.toAbd()); root.values.set(7, functions.toAbd()); return root.toAbd();
    }

    @Test void checkedObjectCodecRoundTripsConcreteAndSymbolicContexts() throws Exception {
        assertEquals(51, ExecOpcodes.code("check_object"));
        assertEquals("check_object", ExecOpcodes.name(51));
        List<AcsObject> programs = new ArrayList<>();
        programs.add(checkedProgram(fixedContext(7, 2, 1), 0, 1, 2, 3, 4, 6, 7, 8));
        programs.add(checkedProgram(fixedContext(8, 3, 2), 0, 6));
        AcsObject buffer = fixedContext(8, 4, 1); buffer.put("element", fixedContext(0, 0, 1));
        programs.add(checkedProgram(buffer));
        AcsObject reference = new AcsObject(); reference.put("ref", 0); programs.add(checkedProgram(reference));
        for (int abi = 0; abi <= 5; abi++) programs.add(checkedProgram(fixedContext(abi, 0, 1)));
        programs.add(checkedProgram(fixedContext(7, 1, 1)));
        for (AcsObject program : programs) {
            byte[] wire = program.toValue().toAbdFormat();
            assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat());
        }
    }

    @Test void checkedObjectCodecRejectsMalformedInspectionAndFieldTypes() throws Exception {
        for (String missing : List.of("v", "context", "slot-types")) {
            AcsObject program = checkedProgram(fixedContext(7, 2, 1), 0); checkedView(program).mmp.remove(missing);
            assertThrows(IllegalArgumentException.class, program::toValue, missing);
        }
        AcsObject extra = checkedProgram(fixedContext(7, 2, 1)); checkedView(extra).put("extra", 0);
        assertThrows(IllegalArgumentException.class, extra::toValue);
        for (int invalid : List.of(-1, 5, 9, Integer.MAX_VALUE)) {
            AcsObject program = checkedProgram(fixedContext(7, 2, 1), invalid);
            assertThrows(IllegalArgumentException.class, program::toValue);
        }
        AcsObject wrongType = checkedProgram(fixedContext(7, 2, 1), 0);
        checkedView(wrongType).getAsAcsArray("slot-types").acsa.set(0, new AcsStringElement("int"));
        assertThrows(IllegalArgumentException.class, wrongType::toValue);
        AcsObject nonArray = checkedProgram(fixedContext(7, 2, 1)); checkedView(nonArray).put("slot-types", "int");
        assertThrows(IllegalArgumentException.class, nonArray::toValue);
        for (int count : List.of(0, 1, 3)) {
            AcsObject mismatch = checkedProgram(fixedContext(8, 3, 2), new int[count]);
            assertThrows(IllegalArgumentException.class, mismatch::toValue);
        }
        AcsObject pointerWidth = checkedProgram(fixedContext(7, 2, 2), 0, 0);
        assertThrows(IllegalArgumentException.class, pointerWidth::toValue);
        AcsObject tooMany = checkedProgram(fixedContext(7, 2, 1));
        checkedView(tooMany).getAsAcsArray("slot-types").acsa.addAll(Collections.nCopies(ExecCodec.MAX_SLOTS + 1, new AcsIntegerElement(0)));
        assertThrows(IllegalArgumentException.class, tooMany::toValue);
    }

    @Test void checkedObjectWireHasExactlyFourFieldsAndStrictSlotTags() throws Exception {
        AbdValue wire = checkedProgram(fixedContext(8, 3, 2), 0, 6).toValue();
        var root = wire.getAsAss(); var function = root.values.get(7).getAsAss().values.get(0).getAsAss();
        var body = function.values.get(5).getAsAss(); var checked = body.values.get(1).getAsAss().values.get(0).getAsAss();
        assertEquals(4, checked.values.size());
        assertEquals(51, AbdBasicType.abd2int(checked.values.get(0)));
        AbdValue shortRecord = raw(checked.values.subList(0, 3));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, shortRecord)));
        List<AbdValue> extra = new ArrayList<>(checked.values); extra.add(checked.values.get(3));
        AbdValue longRecord = raw(extra);
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, longRecord)));
        for (AbdValue slots : List.of(raw(List.of()), raw(List.of(AbdBasicType.int2Abd(0))),
                raw(List.of(AbdBasicType.int2Abd(0), AbdBasicType.int2Abd(5))),
                raw(List.of(AbdBasicType.int2Abd(0), new AbdValue(new byte[]{0}))))) {
            AbdValue invalid = raw(List.of(checked.values.get(0), checked.values.get(1), checked.values.get(2), slots));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, invalid)));
        }
    }
}
