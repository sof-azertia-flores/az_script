package azertia.script;

import azertia.binary.complexBinary.AcsObject;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenericCompilerTest {
    private JsonObject ast(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source); return parser.toObj();
    }
    private JsonObject compile(String source) throws Exception {
        JsonObject tree = ast(source);
        AcsObject first = Compiler.compile(tree), again = Compiler.compile(JsonParser.parseString(tree.toString()).getAsJsonObject());
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
}
