package azertia.script;

import azertia.Main;
import azertia.binary.AbdValue;
import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {
    @TempDir Path temporary;
    private JsonObject compile(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source);
        return Compiler.compile(script.toObj()).toJson().getAsJsonObject();
    }
    private JsonObject main(String source) throws Exception {
        for (JsonElement function : compile(source).getAsJsonArray("f"))
            if (function.getAsJsonObject().get("id").getAsInt() == 0x0fff0000) return function.getAsJsonObject();
        throw new AssertionError("main missing");
    }
    private JsonObject returned(String expression) throws Exception {
        return main("int main(){return " + expression + ";}").getAsJsonArray("script").get(0)
                .getAsJsonObject().getAsJsonObject("r");
    }
    private List<JsonObject> variableInstructions(JsonElement expression) {
        List<JsonObject> instructions = new ArrayList<>();
        if (expression.isJsonArray()) {
            for (JsonElement child : expression.getAsJsonArray()) instructions.addAll(variableInstructions(child));
        } else if (expression.isJsonObject()) {
            JsonObject object = expression.getAsJsonObject();
            if (object.has("t") && object.get("t").isJsonPrimitive() && object.get("t").getAsJsonPrimitive().isNumber()
                    && object.get("t").getAsInt() == 0 && object.has("c")
                    && List.of("v", "vd", "vs").contains(ExecOpcodes.name(object.get("c").getAsInt()))) instructions.add(object);
            for (JsonElement child : object.asMap().values()) instructions.addAll(variableInstructions(child));
        }
        return instructions;
    }
    @Test void arithmeticPrecedenceAndAssociativity() throws Exception {
        JsonObject subtraction = returned("20-3-2");
        assertEquals("minus", ExecOpcodes.name(subtraction.get("c").getAsInt()));
        assertEquals("minus", ExecOpcodes.name(subtraction.getAsJsonObject("v1").get("c").getAsInt()));
        assertEquals(20, subtraction.getAsJsonObject("v1").get("v1").getAsInt());
        JsonObject division = returned("100/5/2");
        assertEquals("divide", ExecOpcodes.name(division.getAsJsonObject("v1").get("c").getAsInt()));
        JsonObject mixed = returned("2+3*(4+1)");
        assertEquals("add", ExecOpcodes.name(mixed.get("c").getAsInt()));
        assertEquals("multiply", ExecOpcodes.name(mixed.getAsJsonObject("v2").get("c").getAsInt()));
        assertEquals("add", ExecOpcodes.name(mixed.getAsJsonObject("v2").getAsJsonObject("v2").get("c").getAsInt()));
        assertEquals(ExecOpcodes.ADD, returned("(2)+3").get("c").getAsInt());
    }
    @Test void stringPunctuationEscapesCommentsAndUnicode() throws Exception {
        var object = main("string main(){ /* } ( ; */ return \"a,(b);{c} \\\"ok\\\" \\\\ \\n\\t\\u4e2d\"; // ignored }\n}");
        assertEquals("a,(b);{c} \"ok\" \\ \n\t中", object.getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsString());
        assertEquals("it's", main("string main(){return 'it\\'s';}").getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsString());
    }
    @Test void callsNestAndResolveForwardAndRecursively() throws Exception {
        JsonObject main = main("int main(){return sum(sum(1,2), fact(4));} int sum(a,b){return a+b;} int fact(n){if(n<=1){return 1;}return n*fact(n-1);}");
        JsonObject call = main.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r");
        assertEquals(1, call.get("t").getAsInt());
        assertEquals(2, call.getAsJsonArray("param").size());
        assertEquals(2, call.getAsJsonArray("param").get(0).getAsJsonObject().getAsJsonArray("param").size());
    }
    @Test void lexicalScopesDoNotAliasSiblingOrOuterLocals() throws Exception {
        JsonArray block = main("int main(){def(x,1);{def(x,2);}{def(y,3);}def(z,4);return x+z;}").getAsJsonArray("script");
        int outer = block.get(0).getAsJsonObject().get("v").getAsInt();
        int inner = block.get(1).getAsJsonArray().get(0).getAsJsonObject().get("v").getAsInt();
        int sibling = block.get(2).getAsJsonArray().get(0).getAsJsonObject().get("v").getAsInt();
        assertNotEquals(outer, inner); assertNotEquals(inner, sibling);
        assertEquals(outer, block.get(4).getAsJsonObject().getAsJsonObject("r").getAsJsonObject("v1").get("v").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){{def(x,1);}return x;}"));
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){def(x);def(x);}"));
    }
    @Test void execUsesNegativeGlobalsAndConsecutiveParameterAndLocalSlots() throws Exception {
        JsonObject tree = newTree("#gvar total\n#gvar label\n"
                + "int helper(int value,string text){def(copy,value);{def(copy,copy+1);total=copy;}label=text;return copy;}"
                + "int main(){return helper(3,\"__func_param0\");}");
        tree.getAsJsonObject("metadata").addProperty("version", 29);
        JsonObject compiled = Compiler.compile(tree).toJson().getAsJsonObject();
        assertEquals(5, compiled.get("exec-version").getAsInt());
        assertEquals(29, compiled.get("version").getAsInt(), "source version is not the executable format version");
        assertTrue(compiled.get("gvs").getAsJsonPrimitive().isNumber());
        assertEquals(2, compiled.get("gvs").getAsInt());
        int helperId = Integer.parseInt(tree.getAsJsonObject("abstract").get("helper").getAsString(), 16);
        JsonObject helper = compiled.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == helperId).findFirst().orElseThrow();
        assertEquals(2, helper.get("param-count").getAsInt());
        assertEquals(2, helper.get("local-count").getAsInt());
        JsonArray body = helper.getAsJsonArray("script");
        assertEquals(2, body.get(0).getAsJsonObject().get("v").getAsInt());
        assertEquals(0, body.get(0).getAsJsonObject().getAsJsonObject("val").get("v").getAsInt());
        JsonArray inner = body.get(1).getAsJsonArray();
        assertEquals(3, inner.get(0).getAsJsonObject().get("v").getAsInt());
        assertEquals(2, inner.get(0).getAsJsonObject().getAsJsonObject("val").getAsJsonObject("v1").get("v").getAsInt());
        assertEquals(-1, inner.get(1).getAsJsonObject().get("v").getAsInt());
        assertEquals(3, inner.get(1).getAsJsonObject().getAsJsonObject("val").get("v").getAsInt());
        assertEquals(-2, body.get(2).getAsJsonObject().get("v").getAsInt());
        assertEquals(1, body.get(2).getAsJsonObject().getAsJsonObject("val").get("v").getAsInt());
        for (JsonElement item : compiled.getAsJsonArray("f")) {
            JsonObject function = item.getAsJsonObject();
            int frameSize = function.get("param-count").getAsInt() + function.get("local-count").getAsInt();
            for (JsonObject instruction : variableInstructions(function.get("script"))) {
                assertTrue(instruction.get("v").getAsJsonPrimitive().isNumber(), instruction::toString);
                int slot = instruction.get("v").getAsInt();
                assertTrue(slot >= -2 && slot < frameSize, instruction::toString);
            }
        }
        JsonObject main = compiled.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == 0x0fff0000).findFirst().orElseThrow();
        assertEquals("__func_param0", main.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r")
                .getAsJsonArray("param").get(1).getAsString(), "string literals must not be treated as variable IDs");
        assertEquals(0, main.get("local-count").getAsInt());
    }
    @Test void localCountsIncludeNestedAndImplicitBlocksAndResetForEachFunction() throws Exception {
        JsonObject function = main("int main(int start){int x=start;{int x=x+1;}"
                + "if(start>0){int y=2;}else{int y=3;}while(x<4){int x=4;break;}return x;}");
        assertEquals(1, function.get("param-count").getAsInt());
        assertEquals(5, function.get("local-count").getAsInt());
        assertEquals(List.of(1, 2, 3, 4, 5), variableInstructions(function.get("script")).stream()
                .filter(instruction -> ExecOpcodes.name(instruction.get("c").getAsInt()).equals("vd"))
                .map(instruction -> instruction.get("v").getAsInt()).toList());
        JsonObject program = compile("int first(int value){int copy=value;return copy;}"
                + "int second(int value){int copy=value;return copy;}int main(){return first(second(2));}");
        for (JsonElement item : program.getAsJsonArray("f")) {
            JsonObject compiledFunction = item.getAsJsonObject();
            if (compiledFunction.get("id").getAsInt() == 0x0fff0000) continue;
            assertEquals(1, compiledFunction.get("local-count").getAsInt());
            assertEquals(1, compiledFunction.getAsJsonArray("script").get(0).getAsJsonObject().get("v").getAsInt());
        }
        JsonObject implicit = main("void main(int value){if(value>0)def(left,1);else def(right,2);while(value<0)def(loop,3);}");
        assertEquals(3, implicit.get("local-count").getAsInt());
    }
    @Test void recursiveForwardFunctionsReuseNumericParametersWithoutChangingReadableAst() throws Exception {
        JsonObject tree = newTree("#gvar saved\nint main(){saved=factorial(4);return saved;}"
                + "int factorial(int n){if(n<=1)return 1;return n*factorial(n-1);}");
        String readable = tree.toString();
        AcsObject first = Compiler.compile(tree);
        assertEquals(readable, tree.toString());
        AcsObject restored = Compiler.compile(JsonParser.parseString(readable).getAsJsonObject());
        assertArrayEquals(first.toValue().toAbdFormat(), restored.toValue().toAbdFormat());
        int factorialId = Integer.parseInt(tree.getAsJsonObject("abstract").get("factorial").getAsString(), 16);
        JsonObject factorial = first.toJson().getAsJsonObject().getAsJsonArray("f").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(function -> function.get("id").getAsInt() == factorialId)
                .findFirst().orElseThrow();
        assertEquals(1, factorial.get("param-count").getAsInt());
        assertEquals(0, factorial.get("local-count").getAsInt());
        assertTrue(variableInstructions(factorial.get("script")).stream().allMatch(instruction -> instruction.get("v").getAsInt() == 0));
        assertEquals(0, compile("void main(){}").get("gvs").getAsInt());
    }
    @Test void assignmentRightAssociatesAndUnaryOperatorsWork() throws Exception {
        JsonArray block = main("int main(){def(a);def(b);a=b=3;return -a + !false;}").getAsJsonArray("script");
        JsonObject assignment = block.get(2).getAsJsonObject();
        assertEquals("vs", ExecOpcodes.name(assignment.get("c").getAsInt()));
        assertEquals("vs", ExecOpcodes.name(assignment.getAsJsonObject("val").get("c").getAsInt()));
        JsonObject result = block.get(3).getAsJsonObject().getAsJsonObject("r");
        assertEquals("neg", ExecOpcodes.name(result.getAsJsonObject("v1").get("c").getAsInt()));
        assertEquals("not", ExecOpcodes.name(result.getAsJsonObject("v2").get("c").getAsInt()));
    }
    @Test void oldAndNewControlSpellingsRetainElseAndNestedBlocks() throws Exception {
        JsonArray body = main("int main(){def(i,0);while(i<3,{if(i==1,{return(4);},{i=i+1;});});if(i>=3){return 7;}else{return 8;}}").getAsJsonArray("script");
        assertEquals("wi", ExecOpcodes.name(body.get(1).getAsJsonObject().get("c").getAsInt()));
        assertTrue(body.get(1).getAsJsonObject().getAsJsonArray("val").get(0).getAsJsonObject().has("else"));
        assertTrue(body.get(2).getAsJsonObject().has("else"));
    }
    @Test void parametersAndInvalidSymbolsGiveDiagnostics() throws Exception {
        assertEquals(2, main("int main(int a,b){return a+b;}").get("param-count").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){return f(1);} int f(a,b){return a+b;}"));
        var unknown = assertThrows(IllegalArgumentException.class, () -> compile("int main(){\n return missing;\n}"));
        assertTrue(unknown.getMessage().contains("main:2:")); assertTrue(unknown.getMessage().contains("missing"));
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){unknown();}"));
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){return 1;}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){return();}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int main(a,a){return a;}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "void __script_onload(int value){} int main(){return 0;}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "int __script_onload(){return 1;} int main(){return 0;}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "int __script_pre_destroy(int value){return value;} int main(){return 0;}"));
    }
    @Test void invalidLexemesBracketsAndNumbersAreRejected() {
        for (String source : new String[]{"void main(){@;}", "void main(){print(\"oops);}", "void main(){/*oops}",
                "void main(){def(x);x=(2+3;}", "void main(){def(x);x=1e+;}", "void main(){2=3;}", "int main(){return 2147483648;}"})
            assertThrows(IllegalArgumentException.class, () -> compile(source), source);
    }
    @Test void integerMinimumAndFractionalLiteralsKeepNumericKinds() throws Exception {
        JsonObject minimum = main("int main(){return -2147483648;}");
        assertEquals(Integer.MIN_VALUE, minimum.getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
        assertEquals(1.25, main("double main(){return 1.25;}").getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsDouble());
        assertEquals(1e100, main("double main(){return 1e100;}").getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsDouble());
    }
    @Test void namespaceExternAndFloatSuffixUseStableIdsAndTypes() throws Exception {
        var script = new GeneraterJson.AzScript();
        script.execute("#namespace 123\nextern int host_add(int, int):0xccf0001;\n"
                + "int helper(){return 2;} int main(){return host_add(helper(), 3);}");
        JsonObject tree = script.toObj();
        assertTrue(tree.getAsJsonObject("body").has("123"));
        assertTrue(tree.getAsJsonObject("body").has("fff"));
        assertEquals("ccf0001", tree.getAsJsonObject("abstract").get("host_add").getAsString());
        JsonArray functions = Compiler.compile(tree).toJson().getAsJsonObject().getAsJsonArray("f");
        assertTrue(functions.asList().stream().anyMatch(item -> item.getAsJsonObject().get("id").getAsInt() == 0x01230001));
        JsonObject main = functions.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("id").getAsInt() == 0x0fff0000).findFirst().orElseThrow();
        JsonObject call = main.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r");
        assertEquals(0x0ccf0001, call.get("id").getAsInt());
        assertEquals(0x01230001, call.getAsJsonArray("param").get(0).getAsJsonObject().get("id").getAsInt());
        var namespaced = new GeneraterJson.AzScript();
        namespaced.execute("#namespace 0x0123\nint helper(){return 1;} int main(){return helper();}");
        assertEquals(0x01230001, namespaced.functions.stream()
                .filter(item -> item.name.equals("helper")).findFirst().orElseThrow().id);

        var floatScript = new GeneraterJson.AzScript();
        floatScript.execute("#define f replacement\nfloat main(){return 1.5f;}");
        AcsObject floatProgram = Compiler.compile(floatScript.toObj());
        AcsObject floatFunction = (AcsObject) floatProgram.getAsAcsArray("f").acsa.get(0);
        AcsObject floatReturn = (AcsObject) floatFunction.getAsAcsArray("script").acsa.get(0);
        assertInstanceOf(AcsFloat.class, floatReturn.mmp.get("r"));
        assertEquals(1.5f, floatReturn.getAsFloat("r"));
        assertEquals(1000.0, main("#define e3 2\ndouble main(){return 1e3;}")
                .getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsDouble());

        for (String invalid : new String[]{"#namespace abd\nint helper(){return 1;}",
                "#namespace 10000\nint helper(){return 1;}", "extern int bad():nope;\nvoid main(){}",
                "extern int builtin():0xabd0001;\nvoid main(){}", "extern int lifecycle():0xfff0000;\nvoid main(){}",
                "float main(){return 1e100f;}"})
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void typedExternSignaturesAndDeclaredScriptTypesReachAbd() throws Exception {
        var script = new GeneraterJson.AzScript();
        script.execute("extern int host_all(int, string, float, double, bool):0xccf0001;\n"
                + "extern void host_sink(string):0xccf0002;\n"
                + "int helper(int value, string label, bool enabled){int copy=value;host_sink(label);return copy;}\n"
                + "int main(){return host_all(7,\"x\",1.5f,2.5,true);}");
        JsonObject tree = script.toObj();
        assertEquals("ccf0001", tree.getAsJsonObject("abstract").get("host_all").getAsString());
        JsonObject readableSignature = tree.getAsJsonObject("extern-signatures").getAsJsonObject("host_all");
        assertEquals("int", readableSignature.get("return-type").getAsString());
        assertEquals(List.of("int", "string", "float", "double", "boolean"),
                readableSignature.getAsJsonArray("param-types").asList().stream().map(JsonElement::getAsString).toList());

        GeneraterJson.AzFunction helper = script.functions.stream()
                .filter(function -> function.name.equals("helper")).findFirst().orElseThrow();
        JsonObject helperTree = tree.getAsJsonObject("body")
                .getAsJsonObject(Integer.toHexString(helper.id >>> 16))
                .getAsJsonObject("_func" + Integer.toHexString(helper.id));
        assertEquals(List.of("int", "string", "boolean"), helperTree.getAsJsonObject("metadata")
                .getAsJsonArray("param-types").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals("int", helperTree.getAsJsonArray("script").get(0).getAsJsonObject()
                .get("declared-type").getAsString());

        AcsObject program = Compiler.compile(tree);
        JsonObject compiledTree = program.toJson().getAsJsonObject();
        JsonObject compiledSignature = compiledTree.getAsJsonArray("extern-signatures").get(0).getAsJsonObject();
        assertEquals(0x0ccf0001, compiledSignature.get("id").getAsInt());
        assertEquals(Compiler.AsTypes.INT_VALUE, compiledSignature.get("return-type").getAsInt());
        assertEquals(List.of(0, 1, 2, 3, 4), compiledSignature.getAsJsonArray("param-types")
                .asList().stream().map(JsonElement::getAsInt).toList());
        JsonObject compiledHelper = compiledTree.getAsJsonArray("f").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(function -> function.get("id").getAsInt() == helper.id)
                .findFirst().orElseThrow();
        assertEquals(List.of(0, 1, 4), compiledHelper.getAsJsonArray("param-types")
                .asList().stream().map(JsonElement::getAsInt).toList());
        assertEquals(Compiler.AsTypes.INT_VALUE, compiledHelper.getAsJsonArray("script").get(0)
                .getAsJsonObject().get("declared-type").getAsInt());
    }
    @Test void typedExternChecksExactArgumentTypesArityAndUnknowns() {
        String declaration = "extern int host_all(int, string, float, double, boolean):0xccf0001;\n";
        for (String call : new String[]{"host_all(1)", "host_all(1,\"x\",1.5f,2.5,true,6)"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> compile(declaration + "int main(){return " + call + ";}"));
            assertTrue(error.getMessage().contains("host_all"), error::getMessage);
            assertTrue(error.getMessage().contains("expects 5"), error::getMessage);
        }
        for (String call : new String[]{
                "host_all(1.0,\"x\",1.5f,2.5,true)",
                "host_all(1,2,1.5f,2.5,true)",
                "host_all(1,\"x\",1.5,2.5,true)",
                "host_all(1,\"x\",1.5f,2.5f,true)",
                "host_all(1,\"x\",1.5f,2.5,1)"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> compile(declaration + "int main(){return " + call + ";}"));
            assertTrue(error.getMessage().contains("host_all argument"), error::getMessage);
            assertTrue(error.getMessage().contains("expected"), error::getMessage);
        }
        var unknown = assertThrows(IllegalArgumentException.class, () -> compile(declaration
                + "int main(){var value=1;return host_all(value,\"x\",1.5f,2.5,true);}"));
        assertTrue(unknown.getMessage().contains("unknown type"), unknown::getMessage);
    }
    @Test void typedExternSyntaxIsMandatoryAndExternalIdsAreUnique() {
        for (String invalid : new String[]{
                "#extern legacy 0xccf0001\nvoid main(){}",
                "extern int named(value):0xccf0001;\nvoid main(){}",
                "extern int no_id();\nvoid main(){}",
                "extern int too_wide():0x123400001;\nvoid main(){}",
                "extern int bad(void):0xccf0001;\nvoid main(){}",
                "extern int bad(any):0xccf0001;\nvoid main(){}",
                "int bad(void){return 1;} void main(){}",
                "extern int first():0xccf0001;\nextern string second():0xccf0001;\nvoid main(){}",
                "extern int print():0xccf0001;\nvoid main(){}"})
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
        assertDoesNotThrow(() -> compile(
                "extern int wide_id():0x12340001;\nint main(){return wide_id();}"));
        assertDoesNotThrow(() -> compile(
                "extern int unsigned_id():0xf2340001;\nint main(){return unsigned_id();}"));
    }
    @Test void declaredReturnsFlowThroughNestedCallsAndVoidCannotBeAValue() throws Exception {
        JsonObject main = main("extern int host_inner(string):0xccf0001;\n"
                + "extern string host_outer(int):0xccf0002;\n"
                + "string main(){return host_outer(host_inner(\"x\"));}");
        JsonObject outer = main.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r");
        assertEquals(0x0ccf0002, outer.get("id").getAsInt());
        assertEquals(0x0ccf0001, outer.getAsJsonArray("param").get(0).getAsJsonObject().get("id").getAsInt());
        var nestedMismatch = assertThrows(IllegalArgumentException.class, () -> compile(
                "extern string host_inner():0xccf0001;\nextern string host_outer(int):0xccf0002;\n"
                        + "string main(){return host_outer(host_inner());}"));
        assertTrue(nestedMismatch.getMessage().contains("host_outer argument 1"), nestedMismatch::getMessage);
        assertTrue(nestedMismatch.getMessage().contains("expected int, got string"), nestedMismatch::getMessage);

        assertDoesNotThrow(() -> compile("extern void host_sink():0xccf0003;\nvoid main(){host_sink();}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "extern void host_sink():0xccf0003;\nint main(){return host_sink();}"));
        var wrongReturn = assertThrows(IllegalArgumentException.class, () -> compile(
                "extern int host_number():0xccf0004;\nstring main(){return host_number();}"));
        assertTrue(wrongReturn.getMessage().contains("return expected string, got int"), wrongReturn::getMessage);
        assertDoesNotThrow(() -> compile(
                "extern int host_number():0xccf0004;\ndouble main(){return host_number();}"));
    }
    @Test void typedScriptParametersAreExactWhileBareParametersRemainAny() throws Exception {
        assertDoesNotThrow(() -> compile(
                "int typed(int value, label){return value;} int main(){return typed(1,\"anything\");}"));
        var mismatch = assertThrows(IllegalArgumentException.class, () -> compile(
                "int typed(int value){return value;} int main(){return typed(1.0);}"));
        assertTrue(mismatch.getMessage().contains("typed argument 1 expected int, got double"), mismatch::getMessage);
        var unknown = assertThrows(IllegalArgumentException.class, () -> compile(
                "int typed(int value){return value;} int main(){var x=1;return typed(x);}"));
        assertTrue(unknown.getMessage().contains("unknown type"), unknown::getMessage);
        var script = new GeneraterJson.AzScript();
        script.execute("int passthrough(value){return 1;} int main(){return passthrough(\"dynamic\");}");
        int passthrough = script.functions.stream().filter(function -> function.name.equals("passthrough"))
                .findFirst().orElseThrow().id;
        JsonObject compiled = Compiler.compile(script.toObj()).toJson().getAsJsonObject();
        JsonObject function = compiled.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("id").getAsInt() == passthrough).findFirst().orElseThrow();
        assertEquals(Compiler.AsTypes.ANY_VALUE, function.getAsJsonArray("param-types").get(0).getAsInt());
        var uninitialized = assertThrows(IllegalArgumentException.class, () -> compile(
                "extern int consume(int):0xccf0001;\nint main(){int value;return consume(value);}"));
        assertTrue(uninitialized.getMessage().contains("must have an initializer"), uninitialized::getMessage);
    }
    @Test void builtinSignaturesAndKnownOperatorOperandsAreChecked() throws Exception {
        var direct = new GeneraterJson.AzScript();
        direct.execute("void main(){print(1);getDepth();mem_free(1);alloc(1);make_free(1);mem_send_up(1);mem_get(1);}");
        JsonObject directTree = direct.toObj();
        assertFalse(directTree.getAsJsonObject("abstract").has("print"));
        JsonArray calls = Compiler.compile(directTree).toJson().getAsJsonObject().getAsJsonArray("f")
                .get(0).getAsJsonObject().getAsJsonArray("script");
        assertEquals(List.of(0x0abd0000, 0x0abd0001, 0x0abd0002, 0x0abd0003,
                        0x0abd0004, 0x0abd0005, 0x0abd0006),
                calls.asList().stream().map(JsonElement::getAsJsonObject)
                        .map(call -> call.get("id").getAsInt()).toList());
        assertDoesNotThrow(() -> compile(
                "int take(int value){return value;} int main(){return take(alloc(1));}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "int main(){return alloc(\"one\");}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "int main(){return print(1);}"));
        assertDoesNotThrow(() -> compile("void main(){print(1);}"));
        String[] builtinNames = {"print", "getDepth", "mem_free", "alloc", "make_free", "mem_send_up", "mem_get"};
        for (String builtin : builtinNames) {
            assertThrows(IllegalArgumentException.class,
                    () -> compile("void " + builtin + "(){} void main(){}"), builtin);
            assertThrows(IllegalArgumentException.class,
                    () -> compile("extern void " + builtin + "():0xccf0001;\nvoid main(){}"), builtin);
            var reserved = new GeneraterJson.AzScript(); reserved.execute("void main(){}");
            JsonObject reservedTree = reserved.toObj();
            reservedTree.getAsJsonObject("abstract").addProperty(builtin, "ccf0001");
            assertThrows(IllegalArgumentException.class, () -> Compiler.compile(reservedTree), builtin);
        }
        assertThrows(IllegalArgumentException.class, () -> compile(
                "#unknown_directive ignored\nvoid main(){}"));
        assertThrows(IllegalArgumentException.class, () -> compile(
                "#ifdef DISABLED\n#unknown_directive ignored\n#fi\nvoid main(){}"));
        var forgedRuntime = new GeneraterJson.AzScript(); forgedRuntime.execute("void main(){}");
        JsonObject forgedRuntimeTree = forgedRuntime.toObj();
        forgedRuntimeTree.getAsJsonObject("abstract").addProperty("hidden", "abd0000");
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(forgedRuntimeTree));
        var forgedAlias = new GeneraterJson.AzScript();
        forgedAlias.execute("int helper(){return 1;} int main(){return helper();}");
        JsonObject forgedAliasTree = forgedAlias.toObj();
        String helperId = forgedAliasTree.getAsJsonObject("abstract").get("helper").getAsString();
        forgedAliasTree.getAsJsonObject("abstract").addProperty("helper_again", helperId);
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(forgedAliasTree));
        for (String invalidBuiltinCall : new String[]{
                "void main(){print();}", "void main(){print(1,2);}", "void main(){getDepth(1);}",
                "void main(){mem_free(\"x\");}", "void main(){alloc(\"x\");}",
                "void main(){make_free(\"x\");}", "void main(){mem_send_up(\"x\");}",
                "void main(){mem_get(\"x\");}"})
            assertThrows(IllegalArgumentException.class, () -> compile(invalidBuiltinCall), invalidBuiltinCall);
        assertDoesNotThrow(() -> compile("int main(){return getDepth();}"));
        assertDoesNotThrow(() -> compile("boolean main(){return mem_free(1);}"));
        assertDoesNotThrow(() -> compile("int main(){return alloc(1);}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){return mem_free(1);}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){return make_free(1);}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int main(){return mem_send_up(1);}"));
        for (String body : new String[]{
                "return \"a\">\"b\";", "return \"a\"&&true;", "return !\"a\";",
                "if(\"a\"){return true;}return false;"})
            assertThrows(IllegalArgumentException.class,
                    () -> compile("boolean main(){" + body + "}"), body);
        for (String body : new String[]{
                "return \"a\"-1;", "return -\"a\";", "return 1.0%1;"})
            assertThrows(IllegalArgumentException.class,
                    () -> compile("int main(){" + body + "}"), body);
        assertDoesNotThrow(() -> compile("boolean main(){if(1){return true;}return false;}"));
    }
    @Test void breakIsLimitedToLoopsAndMacroExpansionIsBounded() throws Exception {
        JsonArray body = main("int main(){var i=0;while(i<5){i+=1;if(i==2){break;}i+=10;}return i;}")
                .getAsJsonArray("script");
        JsonObject loop = body.get(1).getAsJsonObject();
        JsonObject branch = loop.getAsJsonArray("val").get(1).getAsJsonObject();
        assertEquals("brk", ExecOpcodes.name(branch.getAsJsonArray("val").get(0).getAsJsonObject().get("c").getAsInt()));
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){break;}"));

        StringBuilder source = new StringBuilder("#define M0 1\n");
        for (int i = 1; i <= 21; i++) source.append("#define M").append(i).append(" M").append(i - 1).append("+M").append(i - 1).append('\n');
        source.append("int main(){return M21;}");
        var script = new GeneraterJson.AzScript();
        var error = assertThrows(IllegalArgumentException.class, () -> script.execute(source.toString()));
        assertTrue(error.getMessage().contains("Macro expansion exceeds"));
    }
    @Test void includesAreRelativeAndConditionalMacrosAreTokenAware() throws Exception {
        Files.createDirectories(temporary.resolve("lib"));
        Files.writeString(temporary.resolve("lib/constants.azs"), "#define VALUE 7\n#setattr enabled yes\n");
        Path source = temporary.resolve("program.azs");
        Files.writeString(source, "#include \"lib/constants.azs\"\n#ifdef unknown\n#if_equals enabled yes\nint omitted(){return 0;}\n#else\nint omitted2(){return 0;}\n#fi\n#else\n#if_equals enabled yes\nint main(){return VALUE;}\n#fi\n#fi\nstring label(){return \"VALUE\";}\n");
        var script = new GeneraterJson.AzScript(); script.execute(source);
        assertEquals(2, script.functions.size());
        var functions = Compiler.compile(script.toObj()).toJson().getAsJsonObject().getAsJsonArray("f");
        assertEquals(7, functions.get(0).getAsJsonObject().getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
        assertEquals("VALUE", functions.get(1).getAsJsonObject().getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsString());
        Files.writeString(temporary.resolve("loop.azs"), "#include loop.azs\n");
        assertThrows(IllegalArgumentException.class, () -> script.execute(temporary.resolve("loop.azs")));
        assertThrows(IllegalArgumentException.class, () -> script.execute("#ifdef unclosed\n"));
        assertThrows(IllegalArgumentException.class, () -> script.execute("#else\n"));
        assertThrows(IllegalArgumentException.class, () -> script.execute("#define X X\nint main(){return X;}"));
    }
    @Test void projectExamplesCompileAndRoundTripAbd() throws Exception {
        for (Path source : new Path[]{Path.of("tse.azs"), Path.of("examples/parser-regressions.azs")}) {
            var script = new GeneraterJson.AzScript(); script.execute(source);
            AcsObject compiled = Compiler.compile(script.toObj());
            var decoded = ExecCodec.decode(AbdValue.fromAbd(compiled.toValue().toAbdFormat()));
            assertEquals(compiled.toJson(), decoded.toJson());
        }
    }
    @Test void nativeMemoryAssignmentHasOneTargetEvaluationAndRejectsOtherLvalues() throws Exception {
        JsonArray body = main("int main(){mem_get(next())=42;return 0;} int next(){return 1;}").getAsJsonArray("script");
        JsonObject move = body.get(0).getAsJsonObject();
        assertEquals("m", ExecOpcodes.name(move.get("c").getAsInt()));
        assertEquals(0x0abd0006, move.getAsJsonObject("v1").get("id").getAsInt());
        assertEquals(1, move.getAsJsonObject("v1").getAsJsonArray("param").size());
        assertEquals(42, move.get("v2").getAsInt());
        for (String invalid : new String[]{"mem_get(1)+=2;", "mem_get(1)-=2;", "print(1)=2;", "(1+2)=3;", "mem_get()=2;", "mem_get(1,2)=3;"})
            assertThrows(IllegalArgumentException.class, () -> compile("void main(){" + invalid + "}"), invalid);
    }
    @Test void floatingSpellingsAndNegativeZeroKeepTheirTypesThroughAbd() throws Exception {
        for (String token : new String[]{"5e0", "5.", "5.0", ".5e1"}) {
            var script = new GeneraterJson.AzScript(); script.execute("double main(){return " + token + "/2;}");
            var program = Compiler.compile(script.toObj());
            var function = program.getAsAcsArray("f").acsa.get(0);
            var returned = ((AcsObject) ((AcsObject) function).getAsAcsArray("script").acsa.get(0)).getAsAcsObject("r");
            assertInstanceOf(AcsDouble.class, returned.mmp.get("v1"));
            assertEquals(5.0, returned.getAsDouble("v1"));
        }
        var script = new GeneraterJson.AzScript(); script.execute("double main(){return -0.0;}");
        var decoded = ExecCodec.decode(AbdValue.fromAbd(Compiler.compile(script.toObj()).toValue().toAbdFormat()));
        var function = (AcsObject) decoded.getAsAcsArray("f").acsa.get(0);
        double value = ((AcsObject) function.getAsAcsArray("script").acsa.get(0)).getAsDouble("r");
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(value));
    }
    @Test void preprocessorCommentsDoNotConsumeCodeOrExecuteInactiveDirectives() throws Exception {
        assertEquals(7, main("#define N 7 // value\nint main(){return N;}\n")
                .getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
        assertEquals(1, main("#ifdef OFF\n/*\n#endif\n*/\n#endif\nint main(){return 1;}\n")
                .getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
        assertEquals("https://example/*x*/", main("#define URL \"https://example/*x*/\" // comment\nstring main(){return URL;}")
                .getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsString());
    }
    @Test void cliRejectsNonObjectOrMalformedJsonWithoutThrowing() throws Exception {
        for (String content : new String[]{"[]", "null", "42", "{", "{}",
                "{\"metadata\":{\"author\":{}},\"body\":{}}"}) {
            Path source = temporary.resolve("invalid.json"); Files.writeString(source, content);
            var bytes = new ByteArrayOutputStream(); var errors = new PrintStream(bytes);
            assertEquals(1, Main.run(new String[]{"compile-json", source.toString()}, errors, errors));
            assertTrue(bytes.toString().startsWith("Error: "));
            assertFalse(Files.exists(temporary.resolve("invalid.exec.abd")));
        }
    }
    @Test void deeplyNestedSourceAndAstFailWithDiagnosticsAndLeaveCompilerReusable() throws Exception {
        for (String source : new String[]{
                "int main(){return " + "(".repeat(2000) + "1" + ")".repeat(2000) + ";}",
                "boolean main(){return " + "!".repeat(2000) + "true;}",
                "int main(){var x=1;return " + "x=".repeat(2000) + "1;}",
                "int main(){return " + "1+".repeat(2000) + "1;}"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(source));
            assertTrue(failure.getMessage().contains("nesting"));
        }
        JsonElement nested = new JsonPrimitive(1);
        for (int i = 0; i < 2000; i++) { JsonArray block = new JsonArray(); block.add(nested); nested = block; }
        JsonElement deepExpression = nested;
        assertThrows(IllegalArgumentException.class, () -> Compiler.compileExpression(deepExpression, new Compiler.LogicEnvironment()));
        var script = new GeneraterJson.AzScript(); script.execute("int main(){return 1;}");
        var tree = script.toObj();
        tree.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000").add("script", deepExpression);
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(tree));
        assertEquals(1, main("int main(){return 1;}").getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
        Path json = temporary.resolve("deep.json"); Files.writeString(json, "[".repeat(2000) + "1" + "]".repeat(2000));
        var errors = new PrintStream(new ByteArrayOutputStream());
        assertEquals(1, Main.run(new String[]{"compile-json", json.toString()}, errors, errors));
    }
    @Test void invokerMemorySourceCompilesAndRoundTrips() throws Exception {
        Path source = temporary.resolve("invoker.azs");
        Files.copy(Path.of("../abdJavaInvoker/tse.azs"), source);
        var script = new GeneraterJson.AzScript(); script.execute(source);
        var instructions = Compiler.compile(script.toObj());
        assertEquals(instructions.toJson(), ExecCodec.decode(AbdValue.fromAbd(instructions.toValue().toAbdFormat())).toJson());
        assertTrue(instructions.toJson().toString().contains("\"c\":" + ExecOpcodes.MOVE));
    }
    @Test void cliProducesAllOutputsAndRejectsSourceOverwrite() throws Exception {
        Path source = temporary.resolve("main.azs"), output = temporary.resolve("out/main.abd");
        Files.writeString(source, "int main(){return 6*7;}");
        var messages = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "-o", output.toString(), "--ast", temporary.resolve("ast.json").toString(), "--exec-json", temporary.resolve("exec.json").toString()}, messages, messages));
        assertTrue(Files.size(output) > 0); assertTrue(Files.exists(temporary.resolve("ast.json")));
        assertEquals(1, Main.run(new String[]{"compile", source.toString(), "-o", source.toString()}, messages, messages));
        assertEquals("int main(){return 6*7;}", Files.readString(source));
    }
    @Test void flatOperatorChainsFitTheAbdLimitAndDeeperOnesAreLocated() throws Exception {
        assertDoesNotThrow(() -> compile("int main(){return " + "1+".repeat(100) + "1;}"));
        assertDoesNotThrow(() -> compile("boolean main(){return " + "true&&".repeat(100) + "true;}"));
        assertDoesNotThrow(() -> compile("string main(){return " + "\"a\"+".repeat(100) + "\"b\";}"));
        assertDoesNotThrow(() -> compile("int main(){" + "if(true){".repeat(40) + "return 1;" + "}".repeat(40) + "return 0;}"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> compile("int main(){\nreturn " + "1+".repeat(130) + "1;}"));
        assertTrue(failure.getMessage().contains("ABD limit"), failure::getMessage);
        assertTrue(failure.getMessage().contains("main:2:"), failure::getMessage);
        var calls = assertThrows(IllegalArgumentException.class,
                () -> compile("int id(int v){return v;} int main(){return " + "id(".repeat(70) + "1" + ")".repeat(70) + ";}"));
        assertTrue(calls.getMessage().contains("ABD limit"), calls::getMessage);
    }
    @Test void reservedWordsCannotBeDeclaredAndByteOrderMarkIsIgnored() throws Exception {
        for (String source : new String[]{"int while(){return 1;} int main(){return 2;}",
                "int main(){def(if,1);return 1;}", "int main(){var return=1;return 1;}",
                "int main(int else){return 1;}", "int main(){int int=1;return 1;}",
                "int main(){def(bool);return 1;}", "#gvar def\nint main(){return 1;}",
                "#define while 1\nint main(){return 1;}", "extern int if():0xccf0001;\nint main(){return 1;}"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(source), source);
            assertTrue(failure.getMessage().contains("reserved word") || failure.getMessage().contains("type name"), failure::getMessage);
        }
        assertEquals(5, main("﻿#define X 5\nint main(){return X;}")
                .getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsInt());
    }
    @Test void nonVoidFunctionsMustReturnOnEveryPath() throws Exception {
        for (String source : new String[]{"int f(){} int main(){return f();}",
                "int f(x){if(x){return 1;}} int main(){return f(1);}",
                "int f(x){while(x){return 1;}} int main(){return f(1);}",
                "int f(x){while(true){if(x){break;}return 1;}} int main(){return f(1);}",
                "int f(x){if(x,{return 1;},{});} int main(){return f(1);}"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(source), source);
            assertTrue(failure.getMessage().contains("without returning"), failure::getMessage);
        }
        for (String source : new String[]{"int f(x){if(x){return 1;}else{return 2;}} int main(){return f(1);}",
                "int f(x){while(true){return 1;}} int main(){return f(1);}",
                "int f(x){if(x,{return 1;},{return 2;});} int main(){return f(1);}",
                "int f(x){while(true){if(x){return 1;}}} int main(){return f(1);}",
                "int f(x){while(1){while(x){break;}return 3;}} int main(){return f(1);}",
                "void g(){} int main(){return 1;}"})
            assertDoesNotThrow(() -> compile(source), source);
    }
    @Test void externCanReferToIndependentlyLoadedScriptNamespaces() {
        for (String source : new String[]{"extern int host():0xfff0009;\nint main(){return host();}",
                "extern int host():0x5;\nint main(){return host();}",
                "#namespace 123\nextern int host():0x1230009;\nint main(){return host();}"})
            assertDoesNotThrow(() -> compile(source), source);
    }
    @Test void classesKeepReadableMetadataAndEraseReferencesAtTheAbdBoundary() throws Exception {
        var script = new GeneraterJson.AzScript();
        script.execute("class Pair{int x;int y;Pair(int x,int y){this.x=x;this.y=y;}"
                + "int sum(){return x+y;}~Pair(){print(x);}}"
                + "Pair identity(Pair p){return p;}void main(){Pair a(1,2);Pair b=new Pair(3,4);Pair alias=a;delete b;}");
        JsonObject tree = script.toObj();
        assertEquals(2, tree.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").size());
        String original = tree.toString();
        JsonObject compiled = Compiler.compile(tree).toJson().getAsJsonObject();
        assertEquals(compiled, Compiler.compile(JsonParser.parseString(original).getAsJsonObject()).toJson());
        assertEquals(original, tree.toString(), "compilation must not rewrite the exported AST");
        int ctor = Integer.parseInt(tree.getAsJsonObject("abstract").get("Pair::<ctor>").getAsString(), 16);
        JsonObject constructor = compiled.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == ctor).findFirst().orElseThrow();
        assertEquals(3, constructor.get("param-count").getAsInt());
        assertEquals(0, constructor.get("local-count").getAsInt());
        assertEquals(List.of(0, 0, 0), constructor.getAsJsonArray("param-types").asList().stream().map(JsonElement::getAsInt).toList());
        assertEquals("oa", ExecOpcodes.name(constructor.getAsJsonArray("script").get(0).getAsJsonObject().get("c").getAsInt()));
        assertEquals(0, constructor.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("v").get("v").getAsInt());
        for (JsonElement element : compiled.getAsJsonArray("f")) {
            JsonObject function = element.getAsJsonObject();
            assertTrue(function.get("return-type").getAsInt() < 7);
            for (JsonElement type : function.getAsJsonArray("param-types")) assertTrue(type.getAsInt() < 7);
            for (JsonObject variable : variableInstructions(function.get("script")))
                assertTrue(variable.get("v").getAsJsonPrimitive().isNumber());
        }
        String instructions = compiled.toString();
        assertTrue(instructions.contains("\"c\":" + ExecOpcodes.code("ob")));
        assertTrue(instructions.contains("\"c\":" + ExecOpcodes.code("od")));
        assertTrue(instructions.contains("\"c\":" + ExecOpcodes.code("ro")));
        assertTrue(instructions.contains("\"offset\":1"));
        assertTrue(instructions.contains("\"id\":" + 0x0abd0003 + ",\"param\":[2]"), instructions);
    }
    @Test void classFieldsKeepTheirTypesThroughReadsCallsAndDefaults() throws Exception {
        String source = "class Values{int i;float f;double d;boolean b;string s;Values next;Values self(){return this;}}"
                + "int takeInt(int x){return x;}float takeFloat(float x){return x;}double takeDouble(double x){return x;}"
                + "boolean takeBool(boolean x){return x;}string takeString(string x){return x;}"
                + "void main(){Values a;int i=takeInt(a.i);float f=takeFloat(a.f);double d=takeDouble(a.d);"
                + "boolean b=takeBool(a.b);string s=takeString(a.s);Values n=a.self();n.next=null;delete null;}";
        JsonObject compiled = compile(source);
        assertDoesNotThrow(() -> ExecCodec.decode(AbdValue.fromAbd(Compiler.compile(newTree(source)).toValue().toAbdFormat())));
        assertFalse(compiled.toString().contains("\"return-type\":100"));
        for (String invalid : new String[]{
                "class P{int x;}void main(){P p;p.x=\"bad\";}",
                "class P{int x;}void main(){P p;string s=p.x;}",
                "class A{int x;}class B{string x;}void main(){A a;B b=a;}",
                "class P{int x;}void main(){P p=0;}",
                "class P{int x;}void main(){P p;int x=p;}",
                "class P{int x;}void main(){P p;print(+p);}",
                "class P{int x;}void main(){P p;print(\"x\"+p);}",
                "class P{int x;}void main(){P p;print(p==0);}"})
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    private JsonObject newTree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    @Test void classReferencesDoNotChangeDynamicVarAndDefContracts() throws Exception {
        String declaration = "class P{int x;int get(){return x;}}";
        for (String body : new String[]{"var p=new P();p.get();", "def(p,new P());p.get();",
                "var p=new P();P q=p;", "var p=new P();delete p;"})
            assertThrows(IllegalArgumentException.class, () -> compile(declaration + "void main(){" + body + "}"), body);
        assertDoesNotThrow(() -> compile(declaration + "void main(){P p=new P();P q=p;q.x=1;delete q;}"));
        assertThrows(IllegalArgumentException.class, () -> compile("int take(int x){return x;}int main(){var x=1;return take(x);}"));
    }
    @Test void classesSupportForwardTypesInitializerOrderAndLexicalMemberResolution() throws Exception {
        assertDoesNotThrow(() -> compile("#gvar x\nclass Box{Point p;int x=2;int later=x+1;"
                + "int value(int x){return x+this.x;}int field(){return x;}}"
                + "class Point{int x;Point self(){return this;}Point(){self().x=6;}}"
                + "Box identity(Box b){return b;}void main(){Box b;Point p;b.p=p;Box alias=identity(b);print(alias.p.x);}"));
        assertDoesNotThrow(() -> compile("class P{int x;int f(int n){if(n<=1){return 1;}return n*f(n-1);}}void main(){P p();print(p.f(5));}"));
        for (String invalid : new String[]{"class P{}void main(){}", "class P{int x;P(int n){}}void main(){P p;}",
                "class P{int x;P(){}P(int n){}}void main(){}", "class P{int x;~P(int n){}}void main(){}",
                "class P{int x;P(){return 1;}}void main(){}", "class P{int x;void bad(){this=null;}}void main(){}",
                "class P{int x;void bad(){x+=1;}}void main(){}", "class P{int x;}void main(){P p;p.x+=1;}",
                "#gvar P\nclass P{int x;}void main(){}", "class P{int x;}int P(){return 1;}void main(){}"})
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void classAstValidatesMetadataAndRejectsInjectedInternalOperations() throws Exception {
        JsonObject original = newTree("class P{int x;int get(){return x;}}void main(){P p;}");
        JsonObject forged = original.deepCopy();
        forged.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("type", "any");
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(forged));
        JsonObject wrongMapping = original.deepCopy();
        wrongMapping.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonObject("methods").addProperty("get", "main");
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(wrongMapping));
        JsonObject forgedInstruction = original.deepCopy();
        JsonArray body = forgedInstruction.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000").getAsJsonArray("script");
        body.add(JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"#allocate\",\"param\":[\"P\"]}"));
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(forgedInstruction));
        JsonObject wrongThis = original.deepCopy();
        String getId = wrongThis.getAsJsonObject("abstract").get("P::get").getAsString();
        wrongThis.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_func" + getId)
                .getAsJsonObject("metadata").getAsJsonArray("param-types").set(0, new JsonPrimitive("int"));
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(wrongThis));
        JsonObject conflictingName = original.deepCopy();
        conflictingName.getAsJsonObject("abstract").addProperty("P", "fff0000");
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(conflictingName));
    }
    @Test void standaloneExpressionEntryAndClassStateRemainReusable() throws Exception {
        var script = new GeneraterJson.AzScript();
        script.execute("class P{int x;int get(){return x;}}int main(){return 0;}");
        JsonElement expression = GeneraterJson.getExpression("new P().get()", 0, script).generate();
        assertEquals("member-call", expression.getAsJsonObject().get("call").getAsString());
        JsonObject tree = script.toObj();
        JsonObject returned = tree.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000")
                .getAsJsonArray("script").get(0).getAsJsonObject();
        returned.getAsJsonArray("param").set(0, expression);
        assertDoesNotThrow(() -> Compiler.compile(tree));
        script.execute("void main(){}"); assertFalse(script.toObj().has("classes"));
        JsonObject withoutClass = script.toObj();
        JsonArray body = withoutClass.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000").getAsJsonArray("script");
        body.add(JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"vardef\",\"param\":[\"p\",0],\"declared-type\":\"P\"}"));
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(withoutClass));
        assertDoesNotThrow(() -> compile("int main(){return 1;}"));
    }
    @Test void memberLoweringCountsAdditionalAbdAddressContainers() throws Exception {
        String prefix = "class P{P next;int x;}int main(){P p;return p";
        assertDoesNotThrow(() -> compile(prefix + ".next".repeat(10) + ".x;}"));
        var error = assertThrows(IllegalArgumentException.class, () -> compile(prefix + ".next".repeat(40) + ".x;}"));
        assertTrue(error.getMessage().contains("ABD limit"), error::getMessage);
        assertTrue(error.getMessage().contains("main:"), error::getMessage);
    }
    @Test void fieldInitializationDiagnosticsRetainTheDeclarationLocation() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> compile("class P{\n  int value=\"wrong\";\n}\nvoid main(){P p;}"));
        assertTrue(error.getMessage().contains("P initialization:2:3"), error::getMessage);
        assertTrue(error.getMessage().contains("value"), error::getMessage);
    }
    @Test void readableMemoryMovesCannotBypassClassFieldOrReferenceTypes() throws Exception {
        for (String target : new String[]{"{\"t\":\"ctrl\",\"call\":\"member\",\"param\":[{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"p\"]},\"x\"]}",
                "{\"t\":\"ctrl\",\"call\":\"var\",\"param\":[\"p\"]}"}) {
            JsonObject tree = newTree("class P{int x;}void main(){P p;}");
            tree.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000").getAsJsonArray("script")
                    .add(JsonParser.parseString("{\"t\":\"ctrl\",\"call\":\"mov\",\"param\":[" + target + ",\"wrong\"]}"));
            assertThrows(IllegalArgumentException.class, () -> Compiler.compile(tree));
        }
    }
    @Test void methodsNamedAfterMemoryBuiltinsCannotCaptureCompilerGeneratedOperations() throws Exception {
        JsonObject tree = newTree("class C{int x;int alloc(int n){return n;}int mem_get(int n){return n+1;}"
                + "void make_free(int n){x=n;}int get(){return x;}}"
                + "void main(){C c;C p=new C();print(c.alloc(9));print(c.mem_get(9));c.make_free(5);print(c.get());delete p;}");
        JsonObject compiled = Compiler.compile(tree).toJson().getAsJsonObject();
        assertEquals(compiled, Compiler.compile(JsonParser.parseString(tree.toString()).getAsJsonObject()).toJson());
        int generatedFactories = 0;
        for (JsonElement item : compiled.getAsJsonArray("f")) {
            JsonObject function = item.getAsJsonObject();
            JsonArray statements = function.getAsJsonArray("script");
            if (statements.asList().stream().noneMatch(statement -> statement.isJsonObject()
                    && statement.getAsJsonObject().has("c") && ExecOpcodes.name(statement.getAsJsonObject().get("c").getAsInt()).equals("ob"))) continue;
            generatedFactories++;
            JsonObject allocation = statements.get(0).getAsJsonObject().getAsJsonObject("val");
            int parameterCount = function.get("param-count").getAsInt();
            assertEquals(1, function.get("local-count").getAsInt(), "factory object address gets one local slot");
            assertEquals(parameterCount, statements.get(0).getAsJsonObject().get("v").getAsInt());
            assertEquals(0x0abd0003, allocation.get("id").getAsInt());
            assertEquals(1, allocation.getAsJsonArray("param").get(0).getAsInt());
            assertEquals(1, statements.get(1).getAsJsonObject().get("t").getAsInt(), "factory directly calls the constructor");
            JsonObject binding = statements.asList().stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                    .filter(statement -> statement.has("c") && ExecOpcodes.name(statement.get("c").getAsInt()).equals("ob")).findFirst().orElseThrow();
            if (binding.get("manual").getAsBoolean())
                assertEquals(0x0abd0004, statements.get(statements.size() - 2).getAsJsonObject().get("id").getAsInt());
        }
        assertEquals(2, generatedFactories);
    }
    private AbdValue rawStack(AbdValue... values) {
        AbdSimpleStack stack = new AbdSimpleStack(); stack.values.addAll(List.of(values)); return stack.toAbd();
    }
    private AbdValue replaceRecordField(AbdValue record, int position, AbdValue replacement) {
        var stack = record.getAsAss(); stack.values.set(position, replacement); return stack.toAbd();
    }
    private AbdValue compactWithBody(AbdValue program, AbdValue body) {
        var root = program.getAsAss(); var functions = root.values.get(7).getAsAss();
        functions.values.set(0, replaceRecordField(functions.values.get(0), 5, body));
        root.values.set(7, functions.toAbd()); return root.toAbd();
    }
    @Test void compactExecHasFixedRecordsAndNumericOpcodesWithoutMapFieldNames() throws Exception {
        AcsObject compiled = Compiler.compile(newTree("int main(){return 1+2;}"));
        assertInstanceOf(ExecProgram.class, compiled);
        assertThrows(IllegalArgumentException.class, compiled::typeValue);
        AbdValue payload = compiled.toValue(); var root = payload.getAsAss().values;
        assertEquals(10, root.size()); assertEquals("AZSCRIPT", AbdBasicType.abd2str(root.get(0)));
        assertEquals(5, AbdBasicType.abd2int(root.get(1)));
        var functionList = root.get(7).getAsAss().values;
        assertEquals(1, functionList.size()); var function = functionList.get(0).getAsAss().values;
        assertEquals(6, function.size()); assertEquals(0x0fff0000, AbdBasicType.abd2int(function.get(0)));
        var block = function.get(5).getAsAss().values;
        assertEquals(2, block.size()); assertEquals(ExecOpcodes.BLOCK, AbdBasicType.abd2int(block.get(0)));
        var returned = block.get(1).getAsAss().values.get(0).getAsAss().values;
        assertEquals(ExecOpcodes.RETURN, AbdBasicType.abd2int(returned.get(0)));
        assertTrue(AbdBasicType.abd2bol(returned.get(1)));
        var addition = returned.get(2).getAsAss().values;
        assertEquals(3, addition.size()); assertEquals(ExecOpcodes.ADD, AbdBasicType.abd2int(addition.get(0)));
        var literal = addition.get(1).getAsAss().values;
        assertEquals(ExecOpcodes.CONSTANT, AbdBasicType.abd2int(literal.get(0)));
        assertEquals(1, new AcsArray(literal.get(1)).acsa.size());
        String wire = new String(payload.getData(), java.nio.charset.StandardCharsets.ISO_8859_1);
        for(String key : List.of("return-type", "param-count", "param-types", "local-count", "script", "exec-version"))
            assertFalse(wire.contains(key), key);
        AcsObject decoded = ExecCodec.decode(payload);
        assertEquals(compiled.toJson(), decoded.toJson());
        assertArrayEquals(payload.toAbdFormat(), decoded.toValue().toAbdFormat());
        for(int opcode = 0; opcode <= ExecOpcodes.BREAK; opcode++)
            assertEquals(opcode, ExecOpcodes.code(ExecOpcodes.name(opcode)));
    }
    @Test void compactExecPreservesScalarTypesAndDynamicExtensionMetadata() throws Exception {
        AcsObject compiled = Compiler.compile(newTree("void main(){}"));
        AcsArray body = compiled.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .findFirst().orElseThrow().getAsAcsArray("script");
        body.acsa.addAll(List.of(new AcsIntegerElement(-1), new AcsFloat(-0.0f), new AcsDouble(-0.0),
                new AcsBooleanElement(true), new AcsStringElement("v vd __func_param0 中文"), new AcsByteArray(new byte[]{(byte)0xff})));
        AcsObject nested = new AcsObject(); nested.put("number", 42); nested.put("values", body);
        compiled.getAsAcsObject("ext").put("custom", nested);
        AcsObject decoded = ExecCodec.decode(compiled.toValue());
        AcsArray values = ((AcsObject) decoded.getAsAcsArray("f").acsa.get(0)).getAsAcsArray("script");
        assertInstanceOf(AcsIntegerElement.class, values.acsa.get(0));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(((AcsFloat) values.acsa.get(1)).getV()));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(((AcsDouble) values.acsa.get(2)).getV()));
        assertInstanceOf(AcsBooleanElement.class, values.acsa.get(3));
        assertEquals("v vd __func_param0 中文", ((AcsStringElement) values.acsa.get(4)).s);
        assertArrayEquals(new byte[]{(byte)0xff}, ((AcsByteArray) values.acsa.get(5)).getBytes());
        assertEquals(42, decoded.getAsAcsObject("ext").getAsAcsObject("custom").getAsInt("number"));
        assertArrayEquals(compiled.toValue().toAbdFormat(), decoded.toValue().toAbdFormat());
    }
    @Test void compactExecRejectsMalformedRecordsTypesWidthsAndLegacyMaps() throws Exception {
        AbdValue program = Compiler.compile(newTree("void main(){}")).toValue();
        var root = program.getAsAss(); root.values.add(AbdBasicType.int2Abd(0));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(root.toAbd()));
        for(AbdValue invalid : List.of(
                replaceRecordField(program, 1, AbdBasicType.int2Abd(3)),
                replaceRecordField(program, 4, new AbdValue(new byte[]{0})),
                replaceRecordField(program, 4, AbdBasicType.int2Abd(-1)),
                replaceRecordField(program, 4, AbdBasicType.int2Abd(ExecCodec.MAX_SLOTS+1))))
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(invalid));
        AbdValue integer = rawStack(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT),
                rawStack(AbdBasicType.int2Abd(3), AbdBasicType.int2Abd(7)));
        for(AbdValue invalid : List.of(
                rawStack(AbdBasicType.int2Abd(30)),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.BREAK), AbdBasicType.int2Abd(0)),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.RETURN), new AbdValue(new byte[]{2})),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.RETURN), AbdBasicType.bol2Abd(false), integer),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.VARIABLE), AbdBasicType.int2Abd(-1)),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.VARIABLE), AbdBasicType.string2Abd("0")),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.OBJECT_BIND), integer, AbdBasicType.int2Abd(0), AbdBasicType.bol2Abd(false)),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT), rawStack(AbdBasicType.int2Abd(1), new AbdValue(new byte[]{(byte)0xff}))),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT), rawStack(AbdBasicType.int2Abd(0xce1066), new AbdValue(new byte[4]))),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT), rawStack(AbdBasicType.int2Abd(0xce867), new AcsFloat(Float.POSITIVE_INFINITY).toValue())),
                rawStack(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT), rawStack(AbdBasicType.int2Abd(0xad), rawStack()))))
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(compactWithBody(program, invalid)));
        AcsObject legacy = new AcsObject(); legacy.put("gvs", new AcsArray()); legacy.put("f", new AcsArray());
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(legacy.toValue()));
        legacy.put("exec-version", 3); legacy.put("gvs", 0);
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(legacy.toValue()));
        AcsObject extra = Compiler.compile(newTree("void main(){}")); extra.put("unexpected", 1);
        assertThrows(IllegalArgumentException.class, extra::toValue);
        AcsObject stringOpcode = Compiler.compile(newTree("int main(){return 1;}"));
        ((AcsObject)((AcsObject)stringOpcode.getAsAcsArray("f").acsa.get(0)).getAsAcsArray("script").acsa.get(0)).put("c", "r");
        assertThrows(IllegalArgumentException.class, stringOpcode::toValue);
    }
    @Test void compactExecEnforcesPhysicalDepthAndKeepsHundredOperationChains() throws Exception {
        AcsObject chain = Compiler.compile(newTree("int main(){return " + "1+".repeat(100) + "1;}"));
        assertArrayEquals(chain.toValue().toAbdFormat(), ExecCodec.decode(chain.toValue()).toValue().toAbdFormat());
        var sourceDepth = assertThrows(IllegalArgumentException.class,
                () -> compile("void main(){" + "if(true){".repeat(45) + "print(1);" + "}".repeat(45) + "}"));
        assertTrue(sourceDepth.getMessage().contains("main:1:"), sourceDepth::getMessage);
        assertTrue(sourceDepth.getMessage().contains("ABD limit"), sourceDepth::getMessage);
        AbdValue program = Compiler.compile(newTree("void main(){}")).toValue();
        AbdValue body = rawStack(AbdBasicType.int2Abd(ExecOpcodes.BREAK));
        for(int i=0;i<64;i++) body=rawStack(AbdBasicType.int2Abd(ExecOpcodes.BLOCK),rawStack(body));
        AbdValue excessive = compactWithBody(program, body);
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(excessive));
        AcsObject cyclic = Compiler.compile(newTree("void main(){}"));
        AcsArray cycle = new AcsArray(); cycle.acsa.add(cycle);
        ((AcsObject)cyclic.getAsAcsArray("f").acsa.get(0)).put("script", cycle);
        assertThrows(IllegalArgumentException.class, cyclic::toValue);
        AcsObject extensions = Compiler.compile(newTree("void main(){}"));
        AcsObject nested = new AcsObject(); AcsObject current = nested;
        for(int i=0;i<128;i++) {AcsObject child=new AcsObject();current.put("child",child);current=child;}
        extensions.put("ext", nested); assertThrows(IllegalArgumentException.class, extensions::toValue);
    }
    @Test void unpairedSurrogatesAreRejectedInsteadOfBecomingQuestionMarks() throws Exception {
        var lone = assertThrows(IllegalArgumentException.class, () -> compile("void main(){print(\"A\\ud800B\");}"));
        assertTrue(lone.getMessage().contains("surrogate"), lone::getMessage);
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){print(\"\\udc00\");}"));
        assertThrows(IllegalArgumentException.class, () -> compile("void main(){print(\"\\ude00\\ud83d\");}"));
        var pair = main("string main(){return \"\\ud83d\\ude00\";}");
        assertEquals("😀", pair.getAsJsonArray("script").get(0).getAsJsonObject().get("r").getAsString());
        JsonObject tree = newTree("string main(){return \"x\";}");
        tree.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000")
                .getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").set(0, new JsonPrimitive("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile(tree));
    }
    @Test void macrosDoNotExpandInsideNumericLiteralSuffixes() throws Exception {
        for (String source : new String[]{
                "#define N 5\nint main(){return 5N;}",
                "#define x10 7\nint main(){return 0x10;}",
                "#define e 9\nint main(){return 2e;}",
                "#define L 1\nint main(){return 100L;}",
                "#define x 5\ndouble main(){return 1.x;}"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> compile(source), source);
            assertTrue(error.getMessage().contains("numeric"), error::getMessage);
        }
        var literals = main("#define N 5\n#define f 1\nint main(){var a=1.5f; var b=2e3; var c=1e-5f; return N;}");
        assertEquals(5, literals.getAsJsonArray("script").get(3).getAsJsonObject().get("r").getAsInt());
        assertEquals(ExecOpcodes.DEFINE, literals.getAsJsonArray("script").get(0).getAsJsonObject().get("c").getAsInt());
    }
    @Test void classDiagnosticsUseSourceLocationsNamesAndUserArgumentNumbers() throws Exception {
        var scoped = assertThrows(IllegalArgumentException.class, () -> compile(
                "class P { int v; P(int a) { v = a; } }\nvoid main() {\n    P p(1, 2);\n}"));
        assertTrue(scoped.getMessage().contains("main:3:"), scoped::getMessage);
        assertTrue(scoped.getMessage().contains("P constructor expects 1 arguments, got 2"), scoped::getMessage);
        var manual = assertThrows(IllegalArgumentException.class, () -> compile(
                "class P { int v; P(int a) { v = a; } }\nvoid main() { P p = new P(\"x\"); }"));
        assertTrue(manual.getMessage().contains("P constructor argument 1 expected int, got string"), manual::getMessage);
        var method = assertThrows(IllegalArgumentException.class, () -> compile(
                "class C { int v; void m(int a) { v = a; } }\nvoid main() { C c(); c.m(1, 2); }"));
        assertTrue(method.getMessage().contains("C::m expects 1 arguments, got 2"), method::getMessage);
        var implicit = assertThrows(IllegalArgumentException.class, () -> compile(
                "class C { int v; void m(int a) { v = a; } void k() { m(\"y\"); } }\nvoid main() {}"));
        assertTrue(implicit.getMessage().contains("C::m argument 1 expected int, got string"), implicit::getMessage);
        for (String source : new String[]{
                "class P {\n    foo v;\n}\nvoid main() {}",
                "class P {\n    int v;\n    void set(void x) { }\n}\nvoid main() {}",
                "class int {\n int v; }\nvoid main() {}"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> compile(source), source);
            assertTrue(error.getMessage().matches("(?s)<source>:\\d+:\\d+: .*"), error::getMessage);
        }
    }
    private JsonObject function(JsonObject program, int id) {
        return program.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("id").getAsInt() == id).findFirst().orElseThrow();
    }
    @Test void fullWidthNamespacesExplicitIdsAndAstRoundTrips() throws Exception {
        JsonObject ast = newTree("#namespace ffff\nint free(){return edge();}int edge():ffff{return 7;}"
                + "int hex():ad23{return free();}int main(){return hex();}");
        AcsObject program = Compiler.compile(ast);
        JsonObject json = program.toJson().getAsJsonObject();
        assertEquals(0xffff0001, function(json, 0xffffad23).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r").get("id").getAsInt());
        assertEquals(-1, function(json, 0xffff0001).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r").get("id").getAsInt());
        assertArrayEquals(program.toValue().toAbdFormat(), Compiler.compile(JsonParser.parseString(ast.toString()).getAsJsonObject()).toValue().toAbdFormat());
        assertArrayEquals(program.toValue().toAbdFormat(), ExecCodec.decode(program.toValue()).toValue().toAbdFormat());
        for (String invalid : List.of("#namespace ffff\n#namespace ffff\nvoid main(){}", "#namespace 1\n#namespace_hint LIB\nvoid main(){}",
                "#namespace_hint LIB\nvoid a():0000{}", "#namespace_hint LIB\nvoid a():0001{}",
                "void __script_onload():0002{}", "void main():0002{}", "void a():10000{}", "void a():1e+2{}"))
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void hintsHaveCanonicalAssumptionsAndRelocatableRecursiveBindings() throws Exception {
        String source = "#namespace_hint LIB\n#assume_hint LIB abcd\n#assume_hint LIB abcd\n"
                + "extern int recurse(int):abcd0007;int recurse(int n){if(n==0)return 0;return recurse(n-1);}"
                + "int main(){return recurse(3);}int helper():ad23{return 1;}";
        JsonObject ast = newTree(source); AcsObject program = Compiler.compile(ast); JsonObject json = program.toJson().getAsJsonObject();
        assertEquals("LIB", json.get("namespace-hint").getAsString());
        assertEquals(1, json.getAsJsonArray("assume-hints").size());
        assertEquals(0xabcd, json.getAsJsonArray("assume-hints").get(0).getAsJsonObject().get("namespace").getAsInt());
        assertEquals(0xabcd0007, function(json, 7).getAsJsonArray("script").get(1).getAsJsonObject().getAsJsonObject("r").get("id").getAsInt());
        assertEquals(0xabcd0007, function(json, 2).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r").get("id").getAsInt());
        assertArrayEquals(program.toValue().toAbdFormat(), Compiler.compile(JsonParser.parseString(ast.toString()).getAsJsonObject()).toValue().toAbdFormat());
        assertEquals(program.toJson(), ExecCodec.decode(program.toValue()).toJson());
        JsonObject automatic = compile("#namespace_hint OWN\n#assume_hint OTHER 1\nextern int host():00020003;int f(){return 1;}");
        assertEquals(3, automatic.getAsJsonArray("assume-hints").get(1).getAsJsonObject().get("namespace").getAsInt());
        for (String invalid : List.of("#namespace_hint 中文\nvoid f(){}", "#assume_hint A 0\nvoid main(){}",
                "#assume_hint A abd\nvoid main(){}", "#assume_hint A fff\nvoid main(){}",
                "#assume_hint A 123\n#assume_hint B 123\nvoid main(){}", "#assume_hint A 123\n#assume_hint A 124\nvoid main(){}",
                "#namespace 123\n#assume_hint A 123\nvoid main(){}",
                "#namespace_hint A\n#assume_hint A 123\nextern int f():01230002;int f():0003{return 1;}"))
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void externDeclarationsMergeAndWinWithoutMovingOtherHintDefinitions() throws Exception {
        String source = "#namespace_hint OWN\n#assume_hint OTHER abcd\nextern int f(int):abcd0009;"
                + "extern int f(int value):abcd0009;int f(int value):0007{return value;}int call(){return f(5);}";
        JsonObject json = compile(source);
        assertEquals(1, json.getAsJsonArray("extern-signatures").size());
        assertEquals(0xabcd0009, function(json, 2).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r").get("id").getAsInt());
        assertEquals(7, function(json, 7).get("id").getAsInt());
        assertDoesNotThrow(() -> Compiler.compile(newTree(source)).toValue());
        for (String invalid : List.of("#extern int f() 0xccf0001\nvoid main(){}", "extern int f():ccf0001;extern int f():ccf0002;void main(){}",
                "extern int f(int):ccf0001;int f(string x){return 1;}void main(){}"))
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void selfImportedSlotsAreReservedBeforePrivateFunctionsAndFactories() throws Exception {
        JsonObject json = compile("#namespace_hint OWN\n#assume_hint OWN abcd\nextern int missing():abcd0003;"
                + "class Data{int x;}int helper(){return 2;}");
        assertFalse(json.getAsJsonArray("f").asList().stream().anyMatch(item -> item.getAsJsonObject().get("id").getAsInt() == 3));
        assertEquals(2, function(json, 2).get("id").getAsInt());
        assertEquals(4, function(json, 4).get("id").getAsInt());
        assertEquals(5, function(json, 5).get("id").getAsInt());
        assertEquals(6, function(json, 6).get("id").getAsInt());
    }
    @Test void externClassesCompileForBothConsumersAndOutOfClassImplementations() throws Exception {
        String header = "#assume_hint POINT abcd\nclass Point{int x=privateSeed();"
                + "extern Point(int value):abcd0002;extern ~Point():abcd0003;extern int get():abcd0004;}";
        JsonObject consumer = compile(header + "void main(){Point p(7);Point q=new Point(8);print(p.get());delete q;}");
        assertEquals(3, consumer.getAsJsonArray("extern-signatures").size());
        for (JsonElement item : consumer.getAsJsonArray("f")) {
            JsonObject function = item.getAsJsonObject();
            if (function.get("id").getAsInt() != 0x0fff0000) {
                assertEquals(0xabcd0002, function.getAsJsonArray("script").get(1).getAsJsonObject().get("id").getAsInt());
                assertEquals(0xabcd0003, function.getAsJsonArray("script").get(2).getAsJsonObject().get("destructor").getAsInt());
            }
        }
        AcsObject library = Compiler.compile(newTree("#namespace_hint POINT\n#gvar libraryState\n" + header
                + "int privateSeed(){return libraryState;}Point::Point(int value):0002{x=x+value;}"
                + "Point::~Point():0003{print(x);}int Point::get():0004{return x;}"));
        JsonObject json = library.toJson().getAsJsonObject();JsonArray ctor = function(json, 2).getAsJsonArray("script");
        assertEquals(ExecOpcodes.OBJECT_ADDRESS, ctor.get(0).getAsJsonObject().get("c").getAsInt());
        assertEquals(ExecOpcodes.MOVE, ctor.get(1).getAsJsonObject().get("c").getAsInt());
        assertEquals(0, ctor.get(1).getAsJsonObject().get("v2").getAsInt());
        assertEquals(0xabcd0005, ctor.get(2).getAsJsonObject().getAsJsonObject("v2").get("id").getAsInt());
        assertEquals(library.toJson(), ExecCodec.decode(library.toValue()).toJson());
        assertFalse(new String(library.toValue().getData(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Point"));
        for (String invalid : List.of("#namespace_hint X\nclass P{int x;int get(){return x;}}",
                header + "int Point::missing(){return 1;}", header + "extern int Point::missing():abcd0010;", header + "int Point::get(int x){return x;}",
                "#namespace_hint POINT\n" + header + "Point::Point(int value):0005{}"))
            assertThrows(IllegalArgumentException.class, () -> compile(invalid), invalid);
    }
    @Test void constructorPrefixesInitializeDefaultsBeforeExpressionsOutsideParameterScope() throws Exception {
        JsonObject ast = newTree("class C{int x=7;int y=x;C(int x){this.x=x;}}void main(){C c(9);}");
        int id = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("C::<ctor>").getAsString(), 16);
        JsonArray body = function(Compiler.compile(ast).toJson().getAsJsonObject(), id).getAsJsonArray("script");
        assertEquals(6, body.size());
        assertEquals(0, body.get(1).getAsJsonObject().get("v2").getAsInt());
        assertEquals(0, body.get(2).getAsJsonObject().get("v2").getAsInt());
        assertEquals(7, body.get(3).getAsJsonObject().get("v2").getAsInt());
        assertEquals(0x0abd0006, body.get(4).getAsJsonObject().getAsJsonObject("v2").get("id").getAsInt());
        assertEquals(1, body.get(5).getAsJsonObject().getAsJsonObject("v2").get("v").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> compile("class C{int x=parameter;C(int parameter){}}void main(){}"));
        assertDoesNotThrow(() -> compile("class C{int x=missingPrivate();extern C():abcd0002;}void main(){C c;}"));
        assertThrows(IllegalArgumentException.class, () -> compile("class C{int x;extern C(int n):abcd0002;}void main(){C c;}"));
    }
    @Test void structuralClassTypesRecurseIgnoreNamesAndNeverBecomeIntegers() throws Exception {
        String declarations = "class A{A next;int x;}class B{B link;int value;int method(){return value;}}";
        assertDoesNotThrow(() -> compile(declarations + "B convert(A a){return a;}void main(){A a;B b=a;b=convert(a);print(a==b);}"));
        assertDoesNotThrow(() -> compile(declarations + "extern A f(B):abcd0002;B f(A a){return a;}void main(){A a;B b=f(a);}"));
        assertDoesNotThrow(() -> compile("class A{B next;}class B{A next;}class C{C next;}void main(){A a;C c=a;}"));
        for (String bad : List.of("class A{A next;int x;}class B{B next;string x;}void main(){A a;B b=a;}",
                "class A{int x;}void main(){A a;int n=a;}", "class A{int x;}void main(){A a=0;}",
                "class A{int x;string y;}class B{string x;int y;}void main(){A a;B b=a;}"))
            assertThrows(IllegalArgumentException.class, () -> compile(bad), bad);
    }
    @Test void v5AssumptionsAndOptionalDestructorHaveStrictFixedRecords() throws Exception {
        AcsObject compiled = Compiler.compile(newTree("class C{int x;extern C():fffe0002;extern ~C():ffffffff;}void main(){C c;}"));
        AcsObject decoded = ExecCodec.decode(compiled.toValue());assertEquals(compiled.toJson(), decoded.toJson());
        JsonObject json = decoded.toJson().getAsJsonObject();
        assertTrue(json.getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .flatMap(f -> f.getAsJsonArray("script").asList().stream()).filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).anyMatch(e -> e.has("destructor") && e.get("destructor").getAsInt() == -1));
        AcsObject without = Compiler.compile(newTree("class C{int x;}void main(){C c;}"));
        assertFalse(without.toJson().toString().contains("destructor"));
        assertEquals(without.toJson(), ExecCodec.decode(without.toValue()).toJson());
        AbdValue basic = Compiler.compile(newTree("void main(){}")).toValue();
        AbdValue assumption = rawStack(AbdBasicType.string2Abd("LIB"), AbdBasicType.int2Abd(0x123));
        for (AbdValue invalid : List.of(replaceRecordField(basic, 1, AbdBasicType.int2Abd(4)),
                replaceRecordField(basic, 8, AbdBasicType.string2Abd("LIB")),
                replaceRecordField(basic, 9, rawStack(assumption, assumption)),
                replaceRecordField(basic, 9, rawStack(rawStack(AbdBasicType.string2Abd("LIB"), AbdBasicType.int2Abd(0xfff)))),
                replaceRecordField(basic, 9, rawStack(rawStack(AbdBasicType.string2Abd("1LIB"), AbdBasicType.int2Abd(0x123))))))
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(invalid));
    }

    @Test void structuralEquivalenceHandlesLongReferenceChainsWithoutJavaRecursion() throws Exception {
        StringBuilder source = new StringBuilder(); int length = 1500;
        for (String prefix : List.of("A", "B")) for (int i = 0; i < length; i++)
            source.append("class ").append(prefix).append(i).append("{").append(prefix).append((i+1)%length).append(" next;}\n");
        source.append("void main(){A0 a;B0 b=a;}");
        assertDoesNotThrow(() -> compile(source.toString()));
        assertThrows(IllegalArgumentException.class, () -> compile("#namespace_hint OWN\n#assume_hint OWN abcd\n"
                + "extern int reserved():abcd0003;int unrelated():0003{return 1;}"));
    }

    @Test void structuralExternAliasesKeepEachDeclaredSurfaceType() throws Exception {
        String classes = "class A{int x;int onlyA(){return x;}}class B{int y;int onlyB(){return y;}}";
        assertDoesNotThrow(() -> compile(classes + "extern A makeA():abcd0002;extern B makeB():abcd0002;"
                + "int main(){return makeA().onlyA()+makeB().onlyB();}"));
        assertDoesNotThrow(() -> compile(classes + "extern A f(B):abcd0002;extern B f(A value):abcd0002;void main(){}"));
        assertThrows(IllegalArgumentException.class, () -> compile("class A{int x;}class B{string y;}"
                + "extern A f():abcd0002;extern B f():abcd0002;void main(){}"));
    }

}
