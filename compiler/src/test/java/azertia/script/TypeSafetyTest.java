package azertia.script;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
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
                // Duplicate externs compare class names, not layouts, so a broken class they agree on reports only its own error.
                new Case("class A : Z{int v;}\nclass Z : A{int w;}\nextern int f(A* a):12340001;\nextern int f(A* other):12340001;\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Inheritance cycle involving A\n<source>:2:7: error: in class Z: Inheritance cycle involving Z"),
                new Case("class A{}\nextern int f(A* a):12340001;\nextern int f(A* other):12340001;\nint main(){return 0;}",
                        "<source>:1:7: error: in class A: Class A must declare at least one field"))) {
            // The parser leaves layouts to the compiler, so the AST exists and compile-json reports the same errors.
            JsonObject tree = tree(item.source());
            assertEquals(item.expected(), assertThrows(CompileException.class, () -> bytes(tree), item.source()).getMessage());
            assertEquals(item.expected(), failure(item.source()).getMessage());
        }
        // Duplicate externs compare class names in the parser, so a genuine conflict is reported before the compiler sees the broken classes.
        assertEquals("<source>:4:13: error: Conflicting extern declaration: f\n<source>:3:13: note: previous declaration is here",
                failure("class A : Z{int v;}\nclass Z : A{int w;}\nextern int f(A* a):12340001;\nextern int f(Z* z):12340001;\nint main(){return 0;}").getMessage());
        // The parser still checks base initializers, and reports each one.
        assertEquals("<source>:3:21: error: Constructor A can initialize only its direct base class\n"
                        + "<source>:4:17: error: Constructor D can initialize only its direct base class",
                failure("class B{int x;}\nclass C{int z;}\nclass A : B{int y; A() : C() {}}\nclass D{int w; D() : B() {}}\nint main(){return 0;}").getMessage());
    }

    @Test void classesAreNominalEvenWithIdenticalLayouts() throws Exception {
        String units = "class Seconds{double v;}\nclass Meters{double v;}\n";
        String base = "class A{int x;}\nclass A2 : A{}\nclass B : A{int y;}\n";
        record Case(String source, String expected) {}
        for (Case item : List.of(
                new Case(units + "double run(Meters* m){return m.v;}\nvoid main(){Seconds* s=new Seconds();print(run(s));delete s;}",
                        "<source>:4:44: error: in function main: run argument 1 expected Meters *, got Seconds *"),
                new Case(units + "void main(){Seconds* s=new Seconds();Meters* m=s;delete s;}",
                        "<source>:3:38: error: in function main: Initializer for m expected Meters *, got Seconds *"),
                new Case(units + "double run(Meters (*) m){return m.v;}\nvoid main(){Seconds s;print(run(s));}",
                        "<source>:4:29: error: in function main: run argument 1 expected Meters (*), got Seconds"),
                new Case(units + "void main(){Seconds s;Meters m=s;}",
                        "<source>:3:23: error: in function main: Initializer for m expected Meters, got Seconds"),
                new Case(units + "void main(){Seconds* s=new Seconds();Meters* m=new Meters();print(s==m);delete s;delete m;}",
                        "<source>:3:68: error: in function main: Object equality requires compatible class references or null"),
                // A derived class without fields of its own is still a different class: no downcast, no slicing.
                new Case(base + "void main(){A* a=new A();A2* b=a;delete a;}",
                        "<source>:4:26: error: in function main: Initializer for b expected A2 *, got A *"),
                new Case(base + "void main(){A2 b;A a=b;}",
                        "<source>:4:18: error: in function main: Initializer for a expected A, got A2"),
                new Case(units + "class M{int n;int operator[](Seconds* k){return n;}void operator[]=(Meters* k,int v){n=v;}}\nint main(){return 0;}",
                        "<source>:3:7: error: in class M: Index getter and setter types must match: M"),
                new Case(units + "extern int f(Seconds* s):12340001;\nextern int f(Meters* m):12340001;\nint main(){return 0;}",
                        "<source>:4:13: error: Conflicting extern declaration: f\n<source>:3:13: note: previous declaration is here"),
                new Case(units + "extern int f(Seconds* s):12340001;\nextern int g(Meters* m):12340001;\nint main(){return 0;}",
                        "<source>:4:13: error: Conflicting external signature ID: 0x12340001"),
                // Declarations and definitions match by class name; the bodies here do not depend on the class.
                new Case(units + "extern int f(Seconds* s):12340001;\nint f(Meters* s):0001{return 1;}\nint main(){return 0;}",
                        "<source>:3:13: error: Extern and definition signatures disagree: f"),
                new Case(units + "extern double Seconds::add(Seconds* o):12340003;\ndouble Seconds::add(Meters* o){return v;}\nint main(){return 0;}",
                        "<source>:3:27: error: Extern and definition signatures disagree: Seconds::add"),
                new Case(units + "extern <T extends Seconds*> int g(T t):12340002;\n<T extends Meters*> int g(T t):0002{return 1;}\nint main(){return 0;}",
                        "<source>:3:34: error: Extern and definition signatures disagree: g")))
            assertEquals(item.expected(), failure(item.source()).getMessage(), item.source());
        // Only a derived pointer converts implicitly, to its base; related pointers compare.
        assertDoesNotThrow(() -> bytes(tree(base + "A* up(B* b){return b;}\nvoid main(){B* b=new B();A* a=up(b);A* c=b;"
                + "print(a==b);print(b==c);A2* d=new A2();a=d;print(a==d);delete b;delete d;}")));
        // A generic application learns its base with its layout; the first function to use it still upcasts.
        String generic = "class Base<T>{T value;}\nclass Child<T> : Base<T>{int extra;}\n";
        for (String first : List.of("Base<int>* up(Child<int>* c){return c;}\n", "void up(Child<int>* c){Base<int>* b=c;}\n",
                "bool up(Base<int>* b,Child<int>* c){return b==c;}\n"))
            assertDoesNotThrow(() -> bytes(tree(generic + first + "void main(){}")), first);
    }

    @Test void valueBranchesHaveACommonTypeAndNeverLaunderReferences() throws Exception {
        JsonObject tree = tree("class A{int x;}\nclass B : A{int y;}\nclass C{int x;}\n"
                + "A* pick(bool c,A* a,B* b,C* other,int n,u){return a;}\nint main(){return 0;}");
        Function<String, Consumer<JsonObject>> returning = body -> copy -> {
            JsonObject returned = function(copy, "pick").getAsJsonArray("script").get(0).getAsJsonObject();
            returned.getAsJsonArray("param").set(0, JsonParser.parseString(body));
        };
        Function<String, Consumer<JsonObject>> declaring = type -> copy ->
                function(copy, "pick").getAsJsonObject("metadata").addProperty("return-type", type);
        String variable = "{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"%s\"]}";
        String choose = "{\"t\":\"ctrl\",\"call\":\"if\",\"param\":[" + variable.formatted("c") + ",%s,%s]}";
        // Derived and base give the base pointer; null takes the other branch's pointer type.
        for (String value : List.of(choose.formatted(variable.formatted("b"), variable.formatted("a")),
                choose.formatted(variable.formatted("a"), "{\"t\":\"ctrl\",\"call\":\"null\",\"param\":[]}"))) {
            JsonObject copy = tree.deepCopy(); returning.apply(value).accept(copy);
            assertDoesNotThrow(() -> bytes(copy), value);
        }
        JsonObject derived = tree.deepCopy();
        returning.apply(choose.formatted(variable.formatted("b"), variable.formatted("a"))).andThen(declaring.apply("B*")).accept(derived);
        assertTrue(assertThrows(CompileException.class, () -> bytes(derived)).getMessage().endsWith("return expected B *, got A *"));
        record Case(String left, String right, String types) {}
        for (Case item : List.of(new Case("a", "other", "A * and C *"), new Case("a", "n", "A * and int"),
                new Case("other", "u", "C * and any"))) {
            CompileException error = rejected(tree, edited -> returning.apply(
                    choose.formatted(variable.formatted(item.left()), variable.formatted(item.right()))).accept(edited));
            assertTrue(error.getMessage().contains("Branches have incompatible types " + item.types()), error::getMessage);
        }
        // The if opcode converts neither arm, so int and double branches have no single numeric type.
        JsonObject numbers = tree("double pick(bool c,int n,double d){double r=d;return r;}\nint main(){return 0;}");
        Function<String, Consumer<JsonObject>> initializing = value -> copy -> function(copy, "pick").getAsJsonArray("script").get(0)
                .getAsJsonObject().getAsJsonArray("param").set(1, JsonParser.parseString(value));
        JsonObject same = numbers.deepCopy();
        initializing.apply(choose.formatted(variable.formatted("d"), variable.formatted("d"))).accept(same);
        assertDoesNotThrow(() -> bytes(same));
        CompileException mixed = rejected(numbers, initializing.apply(choose.formatted(variable.formatted("n"), variable.formatted("d")))::accept);
        assertTrue(mixed.getMessage().endsWith("Initializer for r has unknown type; expected double"), mixed::getMessage);
    }

    @Test void conditionsRequireBooleanAcrossStatementsAndFunctionalForms() throws Exception {
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case("void main(){if(1){}}", "if condition must be boolean, got int"),
                new Case("void main(){while(1.0){}}", "while condition must be boolean, got double"),
                new Case("void main(){for(;1;){}}", "for condition must be boolean, got int"),
                new Case("void main(){if(1,{});}", "if condition must be boolean, got int"),
                new Case("void main(){while(1,{break;});}", "while condition must be boolean, got int"),
                new Case("boolean main(){return !1;}", "not operand must be boolean, got int"),
                new Case("boolean main(){return 1&&true;}", "and left operand must be boolean, got int"),
                new Case("boolean main(){return true&&2.0;}", "and right operand must be boolean, got double"),
                new Case("boolean main(){return 1||false;}", "or left operand must be boolean, got int"),
                new Case("boolean main(){return false||2.0;}", "or right operand must be boolean, got double"),
                new Case("void main(){if(null){}}", "if condition must be boolean, got null"),
                new Case("void main(){address p=null;if(p){}}", "if condition must be boolean, got address"),
                new Case("class P{int x;}void main(){P* p=null;if(p){}}", "if condition must be boolean, got P *"))) {
            String message = failure(item.source()).getMessage();
            assertTrue(message.endsWith(item.message()), message);
            // Reading the same AST back from JSON must enforce the same rule.
            JsonObject parsed = tree(item.source());
            CompileException fromJson = assertThrows(CompileException.class,
                    () -> bytes(JsonParser.parseString(parsed.toString()).getAsJsonObject()));
            assertEquals(message, fromJson.getMessage(), item.source());
        }
        for (String source : List.of(
                "void main(){if(true){}while(false){}for(int i=0;i<2;i++){}}",
                "boolean main(){return !false&&(true||false);}",
                "void main(){if(true,{},{});while(false,{});}",
                // Bare parameters and def/var stay dynamic: the runtime checks the actual tag.
                "boolean check(value){return !value&&(value||true);}void main(){}",
                "void main(){def(x,1);if(x){}while(x){break;}for(;x;){break;}}")) {
            JsonObject parsed = tree(source);
            assertArrayEquals(bytes(parsed), bytes(JsonParser.parseString(parsed.toString()).getAsJsonObject()), source);
        }
        // The loop initializer's declaration is visible when the lowered condition is checked.
        assertTrue(failure("void main(){for(int i=0;i;){}}").getMessage().endsWith("for condition must be boolean, got int"));
    }

    @Test void equalityRequiresCompatiblePrimitiveTypesForBothOperators() throws Exception {
        List<String> types = List.of("int", "float", "double", "boolean", "string", "address");
        for (String operator : List.of("==", "!=")) for (String left : types) for (String right : types) {
            String source = "boolean compare(" + left + " a," + right + " b){return a" + operator + "b;}void main(){}";
            boolean numericPair = types.indexOf(left) < 3 && types.indexOf(right) < 3;
            if (left.equals(right) || numericPair) {
                JsonObject parsed = tree(source);
                assertArrayEquals(bytes(parsed), bytes(JsonParser.parseString(parsed.toString()).getAsJsonObject()), source);
            } else {
                String expected = left.equals("address") || right.equals("address")
                        ? "Address equality requires an address or null"
                        : "Equality requires compatible operand types, got " + left + " and " + right;
                String message = failure(source).getMessage();
                assertTrue(message.endsWith(expected), message);
            }
        }
        for (String source : List.of("boolean main(){return \"abc\"==5;}", "boolean main(){return true!=1;}"))
            assertTrue(failure(source).getMessage().contains("Equality requires compatible operand types"));
        assertDoesNotThrow(() -> bytes(tree("boolean compare(value){return value==1&&value!=\"x\"&&value==true;}void main(){}")));
    }

    @Test void equalityKeepsNominalReferenceChecksAndDoesNotEraseThemThroughAny() throws Exception {
        String classes = "class A{int x;}class B:A{int y;}class C{int x;}";
        assertDoesNotThrow(() -> bytes(tree(classes + "boolean compare(A* a,B* b,address p,value){"
                + "return a==b&&b!=a&&a==null&&null!=b&&p==null&&null==p&&null==null&&value==null;}void main(){}")));
        for (String expression : List.of("a==c", "c!=a", "a==value", "value!=a")) {
            String source = classes + "boolean compare(A* a,C* c,value){return " + expression + ";}void main(){}";
            String message = failure(source).getMessage();
            assertTrue(message.endsWith("Object equality requires compatible class references or null"), message);
        }
        for (String expression : List.of("a==a", "a!=value", "value==a")) {
            String source = classes + "boolean compare(value){A a;return " + expression + ";}void main(){}";
            String message = failure(source).getMessage();
            assertTrue(message.endsWith("Literal objects cannot be compared; compare their fields or pointers"), message);
        }
        String bufferMessage = failure("boolean compare(value){buffer<int> b;return b==value;}void main(){}").getMessage();
        assertTrue(bufferMessage.endsWith("Literal objects cannot be compared; compare their fields or pointers"), bufferMessage);
    }

    @Test void compileJsonCannotBypassConditionOrEqualityRules() throws Exception {
        JsonObject condition = tree("void check(boolean flag){if(flag){}}void main(){}");
        String conditionMessage = rejected(condition, copy -> function(copy, "check").getAsJsonObject("metadata")
                .getAsJsonArray("param-types").set(0, new JsonPrimitive("int"))).getMessage();
        assertTrue(conditionMessage.endsWith("if condition must be boolean, got int"), conditionMessage);
        JsonObject loop = tree("void main(){for(int i=0;i<2;i++){}}");
        String loopMessage = rejected(loop, copy -> function(copy, "main").getAsJsonArray("script").get(0)
                .getAsJsonObject().getAsJsonArray("param").set(1, new JsonPrimitive(1))).getMessage();
        assertTrue(loopMessage.endsWith("for condition must be boolean, got int"), loopMessage);
        JsonObject equality = tree("boolean compare(boolean a,boolean b){return a==b;}void main(){}");
        String equalityMessage = rejected(equality, copy -> {
            JsonArray params = function(copy, "compare").getAsJsonObject("metadata").getAsJsonArray("param-types");
            params.set(0, new JsonPrimitive("string")); params.set(1, new JsonPrimitive("int"));
        }).getMessage();
        assertTrue(equalityMessage.endsWith("Equality requires compatible operand types, got string and int"), equalityMessage);
        // Bare AST parameters retain ANY and must remain eligible for runtime checks.
        JsonObject dynamic = condition.deepCopy();
        function(dynamic, "check").getAsJsonObject("metadata").getAsJsonArray("param-types").set(0, new JsonPrimitive("any"));
        assertDoesNotThrow(() -> bytes(dynamic));
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
    private static JsonObject exec(JsonObject tree) { return Compiler.compile(tree).toJson().getAsJsonObject(); }
    private static JsonObject execFunction(JsonObject exec, int id) {
        return exec.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == id).findFirst().orElseThrow(() -> new AssertionError("Missing function " + id));
    }
    /** The global slot a statement of the exec JSON assigns, or 0 when it is not a global assignment. */
    private static int assignedGlobal(JsonElement statement) {
        JsonObject object = statement.getAsJsonObject();
        return object.get("t").getAsInt() == 0 && object.get("c").getAsInt() == ExecOpcodes.code("vs") ? Math.min(0, object.get("v").getAsInt()) : 0;
    }

    @Test void typedGlobalsGetDefaultsThenInitializersBeforeTheOnloadBody() throws Exception {
        String classes = "class P{int x;P(int n){x=n;}}\n";
        JsonObject tree = tree("#gvar legacy\n" + classes + "int total;\nP * head = new P(total + 3);\nstring label = \"n\";\n"
                + "double ratio;\nfloat f = 1.5f;\nboolean on;\naddress raw;\n"
                + "void __script_onload(){legacy=1;total=7;}\nint main(){return head.x+total;}\n");
        // #gvar names come first in the AST and take the first slots; typed entries follow in source order.
        JsonArray globals = tree.getAsJsonArray("global-variable");
        assertEquals("legacy", globals.get(0).getAsString());
        assertEquals("head", globals.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("P*", globals.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals(4, globals.get(2).getAsJsonObject().get("_line").getAsInt());
        assertFalse(globals.get(1).getAsJsonObject().has("initializer"));
        JsonObject compiled = exec(tree);
        assertEquals(8, compiled.get("gvs").getAsInt());
        JsonArray onload = execFunction(compiled, 0).getAsJsonArray("script");
        // Defaults for -2..-8 in declaration order, then the three initializers, then the body.
        int[] expected = {-2, -3, -4, -5, -6, -7, -8, -3, -4, -6, -1, -2};
        assertEquals(expected.length, onload.size(), onload::toString);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], assignedGlobal(onload.get(i)), onload::toString);
        assertEquals(List.of("0", "{\"address\":\"0\"}", "\"\"", "0.0", "0.0", "false", "{\"address\":\"0\"}"),
                onload.asList().subList(0, 7).stream().map(statement -> statement.getAsJsonObject().get("val").toString()).toList());
        assertEquals("1.5", onload.get(9).getAsJsonObject().get("val").toString());
        // The body keeps its own top level: no extra block wraps it.
        assertEquals(7, onload.get(11).getAsJsonObject().get("val").getAsInt());
        assertArrayEquals(bytes(tree), bytes(JsonParser.parseString(tree.toString()).getAsJsonObject()));
        // Without a user onload, function 0 is synthesized; untyped-only modules are unchanged.
        JsonObject synthesized = exec(tree(classes + "P * head;\nint count = 2;\nint main(){return count;}\n"));
        JsonObject added = execFunction(synthesized, 0);
        assertEquals(5, added.get("return-type").getAsInt());
        assertEquals(3, added.getAsJsonArray("script").size());
        assertTrue(exec(tree("#gvar g\nint main(){return 0;}\n")).getAsJsonArray("f").asList().stream()
                .noneMatch(function -> function.getAsJsonObject().get("id").getAsInt() == 0));
        // An initializer reads globals declared before it and calls functions; it runs in its own scope.
        assertDoesNotThrow(() -> bytes(tree("int seed(){return 4;}\nint a = seed();\nint b = a * 2;\n"
                + "void __script_onload(){int a=1;print(a);}\nint main(){return b;}\n")));
    }

    @Test void typedGlobalsAreTypedEverywhere() throws Exception {
        String base = "class A{int x;}\nclass B : A{int y;}\n";
        assertDoesNotThrow(() -> bytes(tree(base + "A * a = new B();\nB * b;\nvoid keep(A* p){a=p;}\n"
                + "int main(){b=new B();keep(b);a.x=3;int n=a.x;A* copy=a;delete b;a=null;return n;}\n")));
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case(base + "A * a;\nint main(){B* b=a;return 0;}\n", "<source>:4:12: error: in function main: Initializer for b expected B *, got A *"),
                new Case("int n;\nint main(){n=\"x\";return n;}\n", "<source>:2:13: error: in function main: Assignment to n expected int, got string"),
                new Case("int n = 1.5;\nint main(){return n;}\n", "<source>:1:1: error: in global n: Initializer for global n expected int, got double"),
                new Case("string s = f();\nvoid f(){}\nint main(){return 0;}\n", "<source>:1:1: error: in global s: Initializer for global s cannot use a void value"),
                new Case("int n = missing;\nint main(){return n;}\n", "<source>:1:9: error: in global n: Unknown variable: missing")))
            assertEquals(item.message(), failure(item.source()).getMessage(), item.source());
    }

    @Test void everyBadInitializerAndTheOnloadBodyAreReportedTogether() {
        // Each initializer recovers on its own, so a bad one hides neither later initializers nor the onload body.
        String source = "int f(){return \"x\";}\nint a = \"x\";\nint b = \"y\";\nvoid __script_onload(){int q = \"z\";}\nint main(){return \"w\";}\n";
        assertEquals(List.of(
                "<source>:1:9: error: in function f: return expected int, got string",
                "<source>:2:1: error: in global a: Initializer for global a expected int, got string",
                "<source>:3:1: error: in global b: Initializer for global b expected int, got string",
                "<source>:4:24: error: in function __script_onload: Initializer for q expected int, got string",
                "<source>:5:12: error: in function main: return expected int, got string"), failure(source).getMessage().lines().toList());
    }

    @Test void typedGlobalDeclarationsAreRejectedWithLocations() {
        String classes = "class P{int x;P(int n){x=n;}}\n", main = "\nint main(){return 0;}\n";
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case(classes + "P g;" + main, "<source>:2:1: error: in global g: Global g cannot hold a class value or buffer; declare a pointer"),
                new Case(classes + "P g(1);" + main, "<source>:2:1: error: Global g cannot hold a class value or buffer; declare a pointer"),
                new Case(classes + "P * g(1);" + main, "<source>:2:1: error: Global objects cannot be automatic; use = new P(...)"),
                // A prototype is not an automatic object; prototypes stay unsupported.
                new Case(classes + "P * f(int x);\nP * f(int x){return new P(x);}" + main, "<source>:2:13: error: Expected '{', found ';'"),
                new Case("buffer<int> g;" + main, "<source>:1:1: error: in global g: Global g cannot hold a class value or buffer; declare a pointer"),
                new Case("extern int g;" + main, "<source>:1:8: error: Globals cannot be extern"),
                new Case("<T> T g;" + main, "<source>:1:5: error: Globals cannot declare type parameters"),
                new Case("void g;" + main, "<source>:1:1: error: Global g cannot have type void"),
                new Case("int g = 1" + main, "<source>:2:1: error: Expected ';' after global declaration"),
                new Case("int print;" + main, "<source>:1:5: error: Global print conflicts with built-in function print"),
                // The compiler may add the onload, so the lifecycle hook names are never globals.
                new Case("int __script_onload = 5;" + main, "<source>:1:5: error: Global __script_onload conflicts with lifecycle function __script_onload"),
                new Case("int __script_pre_destroy;" + main, "<source>:1:5: error: Global __script_pre_destroy conflicts with lifecycle function __script_pre_destroy"),
                new Case("int ok = 1;\n#gvar __script_onload" + main,
                        "<source>:1:1: error: in global __script_onload: Global __script_onload conflicts with function __script_onload")))
            assertEquals(item.message(), failure(item.source()).getMessage(), item.source());
        record Noted(String source, String message, String note) {}
        for (Noted item : List.of(
                new Noted("#gvar g\nint g;" + main, "<source>:2:5: error: Duplicate global variable: g", "<source>:1:1: note: previous declaration is here"),
                // The error goes to the second declaration, even when the #gvar directive comes second.
                new Noted("int g;\n#gvar g" + main, "<source>:2:1: error: Duplicate global variable: g", "<source>:1:5: note: previous declaration is here"),
                new Noted("int g;\nstring g;" + main, "<source>:2:8: error: Duplicate global variable: g", "<source>:1:5: note: previous declaration is here"),
                new Noted("int g;\nint g(){return 1;}" + main, "<source>:1:5: error: Global g conflicts with function g", "<source>:2:6: note: previous definition is here"),
                new Noted("int f(){return 1;}\nint f = 2;" + main, "<source>:2:5: error: Global f conflicts with function f", "<source>:1:6: note: previous definition is here"),
                new Noted("extern int g():12340001;\nint g;" + main, "<source>:2:5: error: Global g conflicts with function g", "<source>:1:13: note: previous declaration is here"))) {
            String message = failure(item.source()).getMessage();
            assertEquals(List.of(item.message(), item.note()), message.lines().toList(), item.source());
        }
        // A pointer-returning function shares the "P * g(" prefix but still parses.
        assertDoesNotThrow(() -> bytes(tree(classes + "P * make(int n){return new P(n);}\nP * other(n){return new P(1);}\nP * shared;\n"
                + "int main(){P* p=make(2);P* q=other(1);shared=p;int r=shared.x+q.x;delete p;delete q;return r;}\n")));
    }

    @Test void compileJsonChecksTypedGlobalsWithTheSameRules() throws Exception {
        JsonObject tree = tree("<T> extern T pick(T):12340001;\nclass P{int x;}\nint count = 1;\nP * head;\nint main(){return count;}\n");
        assertDoesNotThrow(() -> bytes(tree));
        Function<Consumer<JsonObject>, String> message = edit -> rejected(tree, edit).getMessage();
        java.util.function.BiFunction<JsonObject, Integer, JsonObject> entry = (copy, index) -> copy.getAsJsonArray("global-variable").get(index).getAsJsonObject();
        // The extern's type parameter is out of scope for a global.
        assertEquals("<source>:3:1: error: in global count: Unknown type: T", message.apply(copy -> entry.apply(copy, 0).addProperty("type", "T")));
        assertEquals("<source>:4:1: error: in global head: Global head cannot hold a class value or buffer; declare a pointer",
                message.apply(copy -> entry.apply(copy, 1).addProperty("type", "P")));
        assertEquals("<source>:4:1: error: in global head: Global head cannot hold a class value or buffer; declare a pointer",
                message.apply(copy -> entry.apply(copy, 1).addProperty("type", "buffer<int>")));
        assertEquals("<source>:3:1: error: in global count: void is not a concrete value type", message.apply(copy -> entry.apply(copy, 0).addProperty("type", "void")));
        assertEquals("<source>:3:1: error: in global count: any is not a concrete value type", message.apply(copy -> entry.apply(copy, 0).addProperty("type", "any")));
        assertEquals("<source>:4:1: error: in global count: Duplicate global variable: count", message.apply(copy -> entry.apply(copy, 1).addProperty("name", "count")));
        assertEquals("<source>:3:1: error: in global main: Global main conflicts with function main", message.apply(copy -> entry.apply(copy, 0).addProperty("name", "main")));
        assertEquals("<source>:3:1: error: in global pick: Global pick conflicts with function pick", message.apply(copy -> entry.apply(copy, 0).addProperty("name", "pick")));
        assertEquals("<source>:3:1: error: in global any: 'any' is a reserved word and cannot be used as a global name",
                message.apply(copy -> entry.apply(copy, 0).addProperty("name", "any")));
        assertEquals("<source>:3:1: error: in global count: Initializer for global count expected int, got string",
                message.apply(copy -> entry.apply(copy, 0).addProperty("initializer", "x")));
        assertEquals("<source>:3:1: error: in global __script_onload: Global __script_onload conflicts with lifecycle function __script_onload",
                message.apply(copy -> entry.apply(copy, 0).addProperty("name", "__script_onload")));
        // Malformed entries are shape errors with a JSON pointer; #gvar names precede typed entries.
        assertEquals("<source>: error: Malformed script JSON at /global-variable/2: #gvar names must precede typed globals",
                message.apply(copy -> copy.getAsJsonArray("global-variable").add("count")));
        assertEquals("<source>: error: Malformed script JSON at /global-variable/1/type: required field is missing",
                message.apply(copy -> entry.apply(copy, 1).remove("type")));
        assertEquals("<source>: error: Malformed script JSON at /global-variable/0/name: expected a string, got 3",
                message.apply(copy -> entry.apply(copy, 0).addProperty("name", 3)));
        // A function other than 0 named __script_onload cannot host the initialization.
        assertEquals("<source>:5:9: error: in function __script_onload: Typed globals are initialized in function 0x00000000, but __script_onload has another ID",
                message.apply(copy -> {
                    function(copy, "main").getAsJsonObject("metadata").addProperty("name", "__script_onload");
                    JsonObject ids = copy.getAsJsonObject("abstract"); ids.add("__script_onload", ids.remove("main"));
                }));
    }

    @Test void hintLibrariesCanHoldGenericPointerGlobals() throws Exception {
        JsonObject tree = tree("#namespace_hint BOXES\nclass Box<T>{T value;}\nBox<int>* box = new Box<int>();\n"
                + "int read():0002{return box.value;}\nvoid write(int n):0003{box.value=n;}\nvoid __script_pre_destroy(){delete box;}\n");
        byte[] compiled = bytes(tree);
        assertArrayEquals(compiled, bytes(JsonParser.parseString(tree.toString()).getAsJsonObject()));
        JsonObject onload = execFunction(exec(tree), 0);
        assertEquals(2, onload.getAsJsonArray("script").size());
    }
}
