package azertia.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source/AST contract tests; executable lowering is covered separately. */
class GenericParserTest {
    private JsonObject parse(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private JsonObject function(JsonObject ast, String name) {
        for (JsonElement namespace : ast.getAsJsonObject("body").asMap().values())
            for (JsonElement value : namespace.getAsJsonObject().asMap().values()) {
                JsonObject function = value.getAsJsonObject();
                if (function.getAsJsonObject("metadata").get("name").getAsString().equals(name)) return function;
            }
        throw new AssertionError("Missing function " + name);
    }
    private JsonObject returned(JsonObject function) {
        return function.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
    }

    @Test void genericClassAndMethodsKeepSeparateScopes() throws Exception {
        JsonObject ast = parse("class Box<T> { T value; Box(T value){this.value=value;} T get(){return this.value;}"
                + "<U> U echo(U value){return value;} } <V> V identity(V value){return value;}"
                + "int main(){Box<int> box(3);return box.echo<int>(identity<int>(4));}");
        JsonObject box = ast.getAsJsonArray("classes").get(0).getAsJsonObject();
        assertEquals("T", box.getAsJsonArray("type-parameters").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("T", box.getAsJsonArray("fields").get(0).getAsJsonObject().get("type").getAsString());
        JsonObject get = function(ast, "Box::get").getAsJsonObject("metadata");
        assertEquals("Box<T>*", get.getAsJsonArray("param-types").get(0).getAsString());
        assertFalse(get.has("type-parameters"));
        JsonObject echo = function(ast, "Box::echo").getAsJsonObject("metadata");
        assertEquals("U", echo.getAsJsonArray("type-parameters").get(0).getAsJsonObject().get("name").getAsString());
        JsonObject call = function(ast, "main").getAsJsonArray("script").get(1).getAsJsonObject()
                .getAsJsonArray("param").get(0).getAsJsonObject();
        assertEquals("member-call", call.get("call").getAsString());
        assertEquals("int", call.getAsJsonArray("type-args").get(0).getAsString());
        assertEquals("int", call.getAsJsonArray("param").get(2).getAsJsonObject().getAsJsonArray("type-args").get(0).getAsString());
    }

    @Test void nestedTypesAndPointerFactoriesAreNotInstantiatedAsFunctions() throws Exception {
        JsonObject ast = parse("class Box<T>{T value;} class Pair<A,B>{A first;B second;}"
                + "void main(){Box<Pair<int, Box<string>*>> nested;Box<int>* pointer = new Box<int>();Box<int>* automatic();}");
        JsonArray body = function(ast, "main").getAsJsonArray("script");
        assertEquals("Box<Pair<int,Box<string>*>>", body.get(0).getAsJsonObject().get("declared-type").getAsString());
        assertEquals("Box<int>*", body.get(1).getAsJsonObject().get("declared-type").getAsString());
        assertEquals("Box<int>", body.get(1).getAsJsonObject().getAsJsonArray("param").get(1).getAsJsonObject()
                .getAsJsonArray("param").get(0).getAsString());
        assertEquals("Box<int>", body.get(2).getAsJsonObject().getAsJsonArray("param").get(1).getAsString());
        assertEquals("Box<T>*", function(ast, "Box::<ctor>").getAsJsonObject("metadata").getAsJsonArray("param-types").get(0).getAsString());
    }

    @Test void classOutOfLineBodiesSeeOwnerAndMethodTypeParameters() throws Exception {
        JsonObject ast = parse("#namespace_hint BOX_LIB\n#assume_hint BOX_LIB abcd\n"
                + "class Box<T>{T value;extern Box(T value):abcd0002;extern T get():abcd0003;"
                + "extern <U> U echo(U value):abcd0004;extern ~Box():abcd0005;}"
                + "Box<T>::Box(T value):0002 {this.value=value;}"
                + "T Box<T>::get():0003 {return this.value;}"
                + "<U> U Box<T>::echo(U value):0004 {return value;}Box<T>::~Box():0005 {} ");
        JsonObject metadata = function(ast, "Box::echo").getAsJsonObject("metadata");
        assertEquals("Box", metadata.get("owner-class").getAsString());
        assertEquals("Box<T>*", metadata.getAsJsonArray("param-types").get(0).getAsString());
        assertEquals("U", metadata.getAsJsonArray("type-parameters").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("U", ast.getAsJsonObject("extern-signatures").getAsJsonObject("Box::echo")
                .getAsJsonArray("type-parameters").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test void parameterizedInheritanceAndBoundsSurviveAst() throws Exception {
        JsonObject ast = parse("class Child<T>: public Parent<T>{Child(T value):Parent<T>(value){}}"
                + "class Parent<T>{T value;Parent(T value){this.value=value;}}"
                + "<T extends Parent<int>*> T borrow(T value){return value;}");
        assertEquals("Parent<T>", ast.getAsJsonArray("classes").get(0).getAsJsonObject().get("base").getAsString());
        assertEquals("Parent<T>", function(ast, "Child::<ctor>").getAsJsonObject("metadata").get("base-initializer").getAsString());
        assertEquals("Parent<int>*", function(ast, "borrow").getAsJsonObject("metadata")
                .getAsJsonArray("type-parameters").get(0).getAsJsonObject().get("bound").getAsString());
    }

    @Test void forwardOwnerDeclarationsAndNestedBoundsAreScoped() throws Exception {
        JsonObject ast = parse("T Box<T>::get(){return this.value;}"
                + "class Box<T>{T value;extern T get():0fff0002;}"
                + "class Link<T,U extends Box<T>*>{U value;}"
                + "extern <U> U read(Box<U>(*) box):12340003;");
        assertEquals("Box<T>*", function(ast, "Box::get").getAsJsonObject("metadata").getAsJsonArray("param-types").get(0).getAsString());
        assertEquals("Box<T>*", ast.getAsJsonArray("classes").get(1).getAsJsonObject().getAsJsonArray("type-parameters")
                .get(1).getAsJsonObject().get("bound").getAsString());
        assertEquals("Box<U>(*)", ast.getAsJsonObject("extern-signatures").getAsJsonObject("read").getAsJsonArray("param-types").get(0).getAsString());
    }

    @Test void explicitArgumentsOnRecursiveAndChainedCallsAreRetained() throws Exception {
        JsonObject ast = parse("class Box<T>{T value;Box<T>* self(){return this;}<U> U echo(U x){return x;}}"
                + "<T> T recursive(T x,int n){if(n==0)return x;return recursive<T>(x,n-1);}"
                + "int main(){Box<int> box;return box.self().echo<int>(recursive<int>(4,2));}");
        JsonObject returned = function(ast, "main").getAsJsonArray("script").get(1).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
        assertEquals("int", returned.getAsJsonArray("type-args").get(0).getAsString());
        assertFalse(returned.getAsJsonArray("param").get(0).getAsJsonObject().has("type-args"));
        JsonObject recursion = function(ast, "recursive").getAsJsonArray("script").get(1).getAsJsonObject()
                .getAsJsonArray("param").get(0).getAsJsonObject();
        assertEquals("T", recursion.getAsJsonArray("type-args").get(0).getAsString());
    }

    @Test void duplicateExternsAreAlphaEquivalent() throws Exception {
        JsonObject ast = parse("class Base{int value;}extern <T extends Base*> T identity(T):abcd0002;"
                + "<U extends Base*> extern U identity(U value):abcd0002;");
        assertEquals(1, ast.getAsJsonObject("extern-signatures").size());
        assertThrows(IllegalArgumentException.class, () -> parse("class Base{int value;}"
                + "extern <T extends Base*> T identity(T):abcd0002;extern <U extends Base> U identity(U):abcd0002;"));
    }

    @Test void reflectionAllowsVoidAndCurrentTypeParameter() throws Exception {
        JsonObject ast = parse("<T> T invoke(int id){return reflect_invoke_function<T>(id);}"
                + "void main(){reflect_invoke_function<void>(1234);}");
        assertEquals("T", returned(function(ast, "invoke")).getAsJsonArray("type-args").get(0).getAsString());
        assertEquals("void", function(ast, "main").getAsJsonArray("script").get(0).getAsJsonObject()
                .getAsJsonArray("type-args").get(0).getAsString());
        assertThrows(IllegalArgumentException.class, () -> parse("<T> T id(T x){return x;}void main(){id<void>(1);}"));
    }

    @Test void comparisonsRemainExpressions() throws Exception {
        JsonObject ast = parse("bool main(int a,int b,int c){return a<b>(c);}");
        assertEquals("greater", returned(function(ast, "main")).get("call").getAsString());
        assertEquals("lower", returned(function(ast, "main")).getAsJsonArray("param").get(0).getAsJsonObject().get("call").getAsString());
    }

    @Test void malformedGenericDeclarationsAreRejected() {
        for (String source : new String[]{
                "class Box<T,T>{T value;}", "class Box<int>{int value;}", "class Box<T>{<T> T f(T x){return x;}}",
                "class Box<T>{T value;}void main(){Box raw;}", "class Box<T>{T value;}void main(){Box<int,string> bad;}",
                "class Box<T>{T value;}void main(){Box<void> bad;}", "<T> void f(){T value;}",
                "<T> void f(){T* value=null;}", "class Box<T>{<U> Box(){}}",
                "<T> int main(){return 0;}", "class Box<T>{T value;}T Box<U>::f(){return null;}",
                "class Box<T>{T value;}void main(){Box<int> (*) bad=null;}"})
            assertThrows(IllegalArgumentException.class, () -> parse(source), source);
    }
}
