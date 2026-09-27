package azertia.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Readable AST contracts; buffer storage and operator dispatch are tested separately. */
class BufferOperatorParserTest {
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
    private JsonObject expression(JsonObject ast, String name, int index) {
        return function(ast, name).getAsJsonArray("script").get(index).getAsJsonObject();
    }
    private JsonObject returned(JsonObject ast, String name) {
        return expression(ast, name, 0).getAsJsonArray("param").get(0).getAsJsonObject();
    }

    @Test void bufferDefaultsAndNestedTypesRetainElementTypes() throws Exception {
        JsonObject ast = parse("class Box<T>{buffer<T> values;}"
                + "void main(){buffer<int> first;buffer<buffer<Box<string>*>> nested;buffer<int> copy=first;}");
        JsonObject field = ast.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject();
        assertEquals("buffer<T>", field.get("type").getAsString());
        assertFalse(field.has("initializer"));
        JsonObject first = expression(ast, "main", 0);
        assertEquals("buffer<int>", first.get("declared-type").getAsString());
        JsonObject empty = first.getAsJsonArray("param").get(1).getAsJsonObject();
        assertEquals("buffer-new", empty.get("call").getAsString());
        assertEquals("int", empty.getAsJsonArray("param").get(0).getAsString());
        JsonObject nested = expression(ast, "main", 1);
        assertEquals("buffer<buffer<Box<string>*>>", nested.get("declared-type").getAsString());
        assertEquals("buffer<Box<string>*>", nested.getAsJsonArray("param").get(1).getAsJsonObject().getAsJsonArray("param").get(0).getAsString());
        assertEquals("var", expression(ast, "main", 2).getAsJsonArray("param").get(1).getAsJsonObject().get("call").getAsString());
    }

    @Test void bufferParametersReturnsAndGenericArgumentsAreCanonical() throws Exception {
        JsonObject ast = parse("extern buffer<buffer<int>> import_buffer(buffer<string>):12340002;"
                + "<T> buffer<T> empty(){buffer<T> value;return value;}"
                + "<T> int compare(T a,T b){return value_compare<T>(a,b);}"
                + "void main(){buffer<int> b=empty<int>();}");
        JsonObject imported = ast.getAsJsonObject("extern-signatures").getAsJsonObject("import_buffer");
        assertEquals("buffer<buffer<int>>", imported.get("return-type").getAsString());
        assertEquals("buffer<string>", imported.getAsJsonArray("param-types").get(0).getAsString());
        assertEquals("buffer<T>", function(ast, "empty").getAsJsonObject("metadata").get("return-type").getAsString());
        assertEquals("T", returned(ast, "compare").getAsJsonArray("type-args").get(0).getAsString());
    }

    @Test void allOperatorsHaveDistinctReservedNames() throws Exception {
        JsonObject ast = parse("class Number{int value;"
                + "Number operator+(Number rhs){return this.value;}"
                + "Number operator-(Number rhs){return this.value;}"
                + "Number operator*(Number rhs){return this.value;}"
                + "Number operator/(Number rhs){return this.value;}"
                + "int operator[](int i){return value;}"
                + "void operator[]=(int i,int n){value=n;}"
                + "int operator()(int n,int m){return n+m;} }");
        JsonObject definition = ast.getAsJsonArray("classes").get(0).getAsJsonObject();
        JsonObject operators = definition.getAsJsonObject("operators");
        assertEquals(7, operators.size());
        assertEquals(0, definition.getAsJsonObject("methods").size());
        for (String kind : new String[]{"add", "subtract", "multiply", "divide", "index-get", "index-set", "call"}) {
            String name = "Number::<op:" + kind + ">";
            assertEquals(name, operators.get(kind).getAsString());
            JsonObject metadata = function(ast, name).getAsJsonObject("metadata");
            assertEquals("Number", metadata.get("owner-class").getAsString());
            assertEquals("method", metadata.get("function-kind").getAsString());
            assertEquals(kind, metadata.get("operator-kind").getAsString());
            assertEquals("Number*", metadata.getAsJsonArray("param-types").get(0).getAsString());
        }
    }

    @Test void genericOperatorsCanBeDeclaredAndDefinedAcrossHintBoundaries() throws Exception {
        JsonObject ast = parse("#namespace_hint INDEX_LIB\n#assume_hint INDEX_LIB abcd\n"
                + "class Index<K,V>{buffer<V> values;"
                + "extern V operator[](K key):abcd0002;extern void operator[]=(K key,V value):abcd0003;"
                + "extern V operator()(K key):abcd0004;}"
                + "V Index<K,V>::operator[](K key):0002{return values.get(0);}"
                + "void Index<K,V>::operator[]=(K key,V value):0003{values.set(0,value);}"
                + "V Index<K,V>::operator()(K key):0004{return values.get(0);}");
        JsonObject metadata = function(ast, "Index::<op:index-get>").getAsJsonObject("metadata");
        assertEquals("Index<K,V>*", metadata.getAsJsonArray("param-types").get(0).getAsString());
        assertEquals(2, metadata.get("position").getAsInt());
        JsonObject imported = ast.getAsJsonObject("extern-signatures").getAsJsonObject("Index::<op:index-set>");
        assertEquals("index-set", imported.get("operator-kind").getAsString());
        assertEquals("V", imported.getAsJsonArray("param-types").get(2).getAsString());
        assertFalse(function(ast, "Index::<op:call>").getAsJsonObject("metadata").has("type-parameters"));
    }

    @Test void indicesAndCallsFormPostfixChains() throws Exception {
        JsonObject ast = parse("void main(){xs[key()]=value();xs[1].run(2)[3];make()(4);(xs)(5);xs[0]<int>(6);}");
        JsonObject set = expression(ast, "main", 0);
        assertEquals("index-set", set.get("call").getAsString());
        assertEquals(3, set.getAsJsonArray("param").size());
        assertEquals("key", set.getAsJsonArray("param").get(1).getAsJsonObject().get("call").getAsString());
        JsonObject chain = expression(ast, "main", 1);
        assertEquals("index", chain.get("call").getAsString());
        assertEquals("member-call", chain.getAsJsonArray("param").get(0).getAsJsonObject().get("call").getAsString());
        assertEquals("invoke", expression(ast, "main", 2).get("call").getAsString());
        assertEquals("make", expression(ast, "main", 2).getAsJsonArray("param").get(0).getAsJsonObject().get("call").getAsString());
        assertEquals("invoke", expression(ast, "main", 3).get("call").getAsString());
        assertEquals("int", expression(ast, "main", 4).getAsJsonArray("type-args").get(0).getAsString());
    }

    @Test void binaryAndNamedCallsPreserveExistingAst() throws Exception {
        JsonObject ast = parse("int run(){return a+b*c-d/e;}void main(){f(1);}");
        JsonObject value = returned(ast, "run");
        assertEquals("minus", value.get("call").getAsString());
        assertEquals("add", value.getAsJsonArray("param").get(0).getAsJsonObject().get("call").getAsString());
        assertEquals("divide", value.getAsJsonArray("param").get(1).getAsJsonObject().get("call").getAsString());
        assertEquals("call", expression(ast, "main", 0).get("t").getAsString());
        assertEquals("f", expression(ast, "main", 0).get("call").getAsString());
    }

    @Test void malformedBufferAndOperatorSyntaxIsRejected() {
        for (String source : new String[]{
                "void main(){buffer missing;}", "void main(){buffer<> empty;}",
                "void main(){buffer<int,string> bad;}", "void main(){buffer<void> bad;}",
                "void main(){buffer<int>* bad;}", "void f(buffer<int>(*) value){}",
                "void main(){new buffer<int>();}", "class buffer{int x;}",
                "class Bad:buffer<int>{int x;}", "class C{int x;int operator% (int rhs){return x;}}",
                "class C{int x;int operator+(){return x;}}", "class C{int x;int operator-(int a,int b){return x;}}",
                "class C{int x;void operator+(int rhs){}}", "class C{int x;int operator+(rhs){return x;}}",
                "class C{int x;int operator[](int a,int b){return x;}}",
                "class C{int x;int operator[]=(int key,int value){return x;}}",
                "class C{int x;void operator[]=(int value){}}",
                "class C{int x;<T> T operator()(T value){return value;}}",
                "class C{int x;int operator+(int a){return x;}int operator+(string b){return x;}}",
                "int operator+(int value){return value;}", "void main(){xs[0]+=1;}",
                "void main(){xs[0]++;}", "void main(){xs[]=1;}", "void main(){xs[1,2]=3;}"})
            assertThrows(IllegalArgumentException.class, () -> parse(source), source);
    }
}
