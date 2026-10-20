package azertia.script;

import azertia.Main;
import azertia.binary.complexBinary.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExecOptimizerTest {
    private static JsonObject tree(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source); return parser.toObj();
    }
    private static AcsObject compile(JsonObject ast, boolean debug, Compiler.Optimization level) {
        return Compiler.compile(ast, new Diagnostics(), debug, level);
    }
    private static AcsElement returned(String type, String expression, Compiler.Optimization level) throws Exception {
        AcsObject program = compile(tree(type + " main(){return " + expression + ";}"), false, level);
        for (AcsElement item : program.getAsAcsArray("f").acsa) {
            AcsObject function = (AcsObject) item;
            if (function.getAsInt("id") == 0x0fff0000)
                return ((AcsObject) function.getAsAcsArray("script").acsa.get(0)).mmp.get("r");
        }
        throw new AssertionError("main missing");
    }
    private static AcsElement optimized(String type, String expression) throws Exception {
        return returned(type, expression, Compiler.Optimization.CONSTANTS);
    }

    @Test void checkedIntegerArithmeticAndBitPatternsFold() throws Exception {
        for (var entry : Map.ofEntries(
                Map.entry("(2+3)*4-7", 13), Map.entry("-7/3", -2), Map.entry("-7%3", -1),
                Map.entry("(-2147483648) % -1", 0), Map.entry("1<<31", Integer.MIN_VALUE),
                Map.entry("-1>>31", -1), Map.entry("-1>>>1", Integer.MAX_VALUE),
                Map.entry("(15&6)|(8^3)", 15), Map.entry("~0", -1),
                Map.entry("2147483647 + 0", Integer.MAX_VALUE)).entrySet())
            assertEquals(entry.getValue().intValue(), assertInstanceOf(AcsIntegerElement.class,
                    optimized("int", entry.getKey()), entry.getKey()).s);
    }

    @Test void comparisonsAndBooleanOperationsFold() throws Exception {
        for (String expression : List.of("2+3==5", "2!=3", "2<3", "3>2", "2<=2", "3>=3",
                "!false", "true&&true", "false||true", "true!=false", "false==false"))
            assertTrue(assertInstanceOf(AcsBooleanElement.class, optimized("boolean", expression), expression).on);
        assertFalse(assertInstanceOf(AcsBooleanElement.class, optimized("boolean", "true && (2 > 3)")).on);
    }

    @Test void failuresStayAtRuntimeIncludingIntermediateOverflow() throws Exception {
        for (String expression : List.of("2147483647+1", "-2147483648-1", "65536*65536",
                "-(-2147483647-1)", "(-2147483648)/-1", "1/0", "1%0", "1<<(16+16)",
                "(2147483647+1)-1"))
            assertInstanceOf(AcsObject.class, optimized("int", expression), expression);
    }

    @Test void noIdentitiesDiscardValuesOrChecks() throws Exception {
        JsonObject ast = tree("int effect(){print(7);return 8;}int main(){return effect()*0;}");
        String result = compile(ast, false, Compiler.Optimization.CONSTANTS).toJson().toString();
        assertTrue(result.contains("\"c\":14"), result);
        for (String expression : List.of("1.0+2.0", "0.0*-1.0"))
            assertInstanceOf(AcsObject.class, optimized("double", expression));
        assertInstanceOf(AcsObject.class, optimized("string", "\"a\"+\"b\""));
        assertInstanceOf(AcsObject.class, optimized("address", "alloc(1)+1"));
    }

    @Test void unreachableCodeStillReceivesSemanticChecks() {
        assertThrows(CompileException.class, () -> compile(tree("int main(){if(false){return missing;}return 1+2;}"),
                false, Compiler.Optimization.CONSTANTS));
    }

    @Test void defaultOutputIsUnchangedAndOptimizationActuallyShrinksTheProgram() throws Exception {
        JsonObject ast = tree("int main(){return (2+3)*(4+5);}");
        byte[] original = Compiler.compile(ast).toValue().toAbdFormat();
        assertArrayEquals(original, compile(ast, false, Compiler.Optimization.NONE).toValue().toAbdFormat());
        assertTrue(compile(ast, false, Compiler.Optimization.CONSTANTS).toValue().toAbdFormat().length < original.length);
        assertInstanceOf(AcsObject.class, returned("int", "1+2", Compiler.Optimization.NONE));
    }

    @Test void astAndWireRoundTripsKeepDebugNumbersAndOpaqueMetadataValid() throws Exception {
        String source = "class Box<T>{T value;}class A{int x=2+3;virtual int get(){return x+(3*4);}}"
                + "class B:A{override int get(){return x+(5*6);}}"
                + "int main(){Box<int> b;buffer<int> values;values.reserve(2);values.push(3+4);A* p=new B();"
                + "print(p.get());delete p;return values.get(0)+(1+2);}";
        JsonObject ast = tree(source), saved = ast.deepCopy();
        for (boolean debug : List.of(false, true)) {
            AcsObject program = compile(ast, debug, Compiler.Optimization.CONSTANTS);
            byte[] wire = program.toValue().toAbdFormat();
            assertArrayEquals(wire, compile(JsonParser.parseString(ast.toString()).getAsJsonObject(), debug,
                    Compiler.Optimization.CONSTANTS).toValue().toAbdFormat());
            assertArrayEquals(wire, ExecCodec.decode(program.toValue()).toValue().toAbdFormat());
            assertEquals(saved, ast, "optimization must not change the public AST");
        }
        var plain = compile(ast, false, Compiler.Optimization.CONSTANTS).getAsAcsArray("f").acsa;
        var debug = compile(ast, true, Compiler.Optimization.CONSTANTS).getAsAcsArray("f").acsa;
        for (int i = 0; i < plain.size(); ++i)
            assertEquals(((AcsObject) plain.get(i)).mmp.get("script").toJson(),
                    ((AcsObject) debug.get(i)).mmp.get("script").toJson());
    }

    @Test void commandLineOptimizationIsExplicitAndLastOptionWins() {
        assertEquals(Compiler.Optimization.NONE, Main.parseCompileArguments(List.of("compile", "input.azs")).optimization());
        assertEquals(Compiler.Optimization.STANDARD,
                Main.parseCompileArguments(List.of("compile", "-O0", "input.azs", "-O1")).optimization());
        assertEquals(Compiler.Optimization.NONE,
                Main.parseCompileArguments(List.of("compile-json", "-O1", "input.json", "-O0")).optimization());
        assertThrows(IllegalArgumentException.class, () -> Main.parseCompileArguments(List.of("compile", "input.azs", "-O3")));
    }
}
