package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Bitwise syntax, static checks and exec records stay consistent through compile-json. */
class LanguageOpsTest {
    private static JsonObject tree(String source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script.toObj();
    }
    private static JsonObject main(JsonObject ast) {
        for (JsonElement namespace : ast.getAsJsonObject("body").asMap().values())
            for (JsonElement function : namespace.getAsJsonObject().asMap().values())
                if (function.getAsJsonObject().getAsJsonObject("metadata").get("name").getAsString().equals("main")) return function.getAsJsonObject();
        throw new AssertionError("Missing main");
    }
    private static JsonObject returned(String expression) throws Exception {
        return main(tree("int main(){return " + expression + ";}")).getAsJsonArray("script").get(0)
                .getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
    }
    private static JsonObject operand(JsonObject node, int index) { return node.getAsJsonArray("param").get(index).getAsJsonObject(); }
    private static CompileException failure(String source) {
        return assertThrows(CompileException.class, () -> Compiler.compile(tree(source)), source);
    }
    private static void roundTrip(String source) throws Exception {
        JsonObject ast = tree(source), restored = JsonParser.parseString(ast.toString()).getAsJsonObject();
        for (boolean debug : List.of(false, true)) {
            AcsObject compiled = Compiler.compile(ast, debug);
            AbdValue wire = compiled.toValue();
            assertArrayEquals(wire.toAbdFormat(), Compiler.compile(restored, debug).toValue().toAbdFormat(), source);
            AcsObject decoded = ExecCodec.decode(wire);
            assertEquals(compiled.toJson(), decoded.toJson(), source);
            assertArrayEquals(wire.toAbdFormat(), decoded.toValue().toAbdFormat(), source);
        }
    }

    @Test void bitwiseAndShiftPrecedenceMatchesTheLanguageTable() throws Exception {
        JsonObject node = returned("1 | 2 ^ 3 & 4 == 5 < 6 << 1 + 2 * 3");
        for (String operation : List.of("bit_or", "bit_xor", "bit_and", "cmp", "lower", "shl", "add", "multiply")) {
            assertEquals(operation, node.get("call").getAsString());
            if (!operation.equals("multiply")) node = operand(node, 1);
        }
        JsonObject logical = returned("false || true && (1 & 3) == 1");
        assertEquals("or", logical.get("call").getAsString());
        assertEquals("and", operand(logical, 1).get("call").getAsString());
        assertEquals("cmp", operand(operand(logical, 1), 1).get("call").getAsString());
        JsonObject shifts = returned("32 >> 2 << 1 >>> 3");
        assertEquals("ushr", shifts.get("call").getAsString());
        assertEquals("shl", operand(shifts, 0).get("call").getAsString());
        assertEquals("shr", operand(operand(shifts, 0), 0).get("call").getAsString());
        roundTrip("boolean main(){return false || true && (1 & 3) == 1;}");
    }

    @Test void allCompoundOperatorsKeepTheirFullSpellingAndComplementUsesXor() throws Exception {
        String source = "int main(){int x=-1;x&=255;x|=256;x^=3;x<<=1;x>>=2;x>>>=3;return ~x;}";
        JsonArray statements = main(tree(source)).getAsJsonArray("script");
        List<String> operations = List.of("bit_and", "bit_or", "bit_xor", "shl", "shr", "ushr");
        for (int i = 0; i < operations.size(); i++) {
            JsonObject assignment = statements.get(i + 1).getAsJsonObject();
            assertEquals("varset", assignment.get("call").getAsString());
            assertTrue(assignment.get("compound").getAsBoolean());
            assertEquals(operations.get(i), operand(assignment, 1).get("call").getAsString());
        }
        JsonObject complement = statements.get(7).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
        assertEquals("bit_xor", complement.get("call").getAsString());
        assertEquals(-1, complement.getAsJsonArray("param").get(1).getAsInt());
        roundTrip(source);
    }

    @Test void genericClosersRemainSeparateFromExpressionShifts() throws Exception {
        roundTrip("class Box<T>{T value;}<T> int constant(){return 8;}"
                + "int main(){Box<Box<int>>* box=null;return constant<Box<Box<int>>>()>>1;}");
        roundTrip("<T> T identity(T value){return value;}int main(){return identity<int>(8)<<1;}");
        for (String expression : List.of("8 > > 1", "8 >/* gap */>1", "8 >\n>1", "8 < < 1"))
            assertThrows(CompileException.class, () -> tree("int main(){return " + expression + ";}"), expression);
        // '?' is a reserved token for a later feature, so it reaches a parser diagnostic.
        String unsupported = failure("int main(){return true ? 1 : 2;}").getMessage();
        assertTrue(unsupported.contains("<source>:1:24: error: Expected ';' after statement"), unsupported);
        assertFalse(unsupported.contains("Unexpected character"), unsupported);
    }

    @Test void shiftsUsePreprocessedOffsetsRatherThanMappedMacroColumns() throws Exception {
        for (String source : List.of("#define SHIFT >>\nint main(){return 8 SHIFT 1;}",
                "#define VALUE 8>>1\nint main(){return VALUE;}",
                "#define SHIFT >>>=\nint main(){int n=-1;n SHIFT 1;return n;}"))
            roundTrip(source);
        for (String source : List.of("#define VALUE 8> >1\nint main(){return VALUE;}",
                "#define GT >\nint main(){return 8 GT GT 1;}"))
            assertThrows(CompileException.class, () -> tree(source), source);
    }

    @Test void operandsMustBeIntegerAndLiteralShiftCountsAreChecked() throws Exception {
        for (String expression : List.of("true&1", "1|false", "1^2.0", "1.0<<2", "1>>true", "~false", "1>>>\"x\"")) {
            String message = failure("int main(){return " + expression + ";}").getMessage();
            assertTrue(message.contains("must be int"), message);
            if (expression.contains("true") || expression.contains("false"))
                assertTrue(message.contains("parenthesize comparisons, e.g. (a & b) == 0"), message);
        }
        String precedence = failure("int main(){return 1 & 2 == 0;}").getMessage();
        assertTrue(precedence.contains("parenthesize comparisons"), precedence);
        for (String operator : List.of("<<", ">>", ">>>")) for (String count : List.of("-1", "32", "+32")) {
            String message = failure("int main(){return 1" + operator + count + ";}").getMessage();
            assertTrue(message.contains("Shift count must be between 0 and 31, got " + (count.equals("-1") ? "-1" : "32")), message);
        }
        roundTrip("int bits(value,count){return (value&255)|(value^1)|(value<<count)|(value>>count)|(value>>>count);}"
                + "int main(){return 1<<0|1<<31;}");
        String pointer = failure("class P{int n;}int main(){P* p=null;return p&1;}").getMessage();
        assertTrue(pointer.contains("must be int, got P *"), pointer);
    }

    @Test void compileJsonCannotBypassOperandOrLiteralCountChecks() throws Exception {
        JsonObject ast = tree("int main(){return 8>>1;}");
        JsonObject shift = main(ast).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
        shift.getAsJsonArray("param").set(1, new JsonPrimitive(32));
        String message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.contains("Shift count must be between 0 and 31, got 32"), message);
        shift.getAsJsonArray("param").set(1, new JsonPrimitive(true));
        message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.contains("must be int, got boolean"), message);
        shift.getAsJsonArray("param").remove(1);
        message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.contains("shr expects 2 arguments, got 1"), message);
    }

    private static int targetCode(String target) {
        return switch (target) { case "int" -> 0; case "float" -> 2; case "double" -> 3; case "string" -> 1; case "address" -> 7; default -> throw new AssertionError(target); };
    }
    private static JsonObject execReturn(String source) throws Exception {
        JsonObject program = Compiler.compile(tree(source)).toJson().getAsJsonObject();
        for (JsonElement item : program.getAsJsonArray("f")) {
            JsonObject function = item.getAsJsonObject();
            if (function.get("id").getAsInt() == 0x0fff0000)
                return function.getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonObject("r");
        }
        throw new AssertionError("Missing main");
    }

    @Test void explicitConversionsFollowTheScalarMatrixAndRoundTrip() throws Exception {
        List<String> numeric = List.of("int", "float", "double");
        List<String> targets = List.of("int", "float", "double", "string", "address");
        for (String target : targets) for (String from : List.of("int", "float", "double", "string", "address", "boolean")) {
            String source = target + " main(" + from + " value){return " + target + "(value);}";
            boolean supported = target.equals(from) || numeric.contains(target) && (numeric.contains(from) || from.equals("string"))
                    || target.equals("string") && (numeric.contains(from) || from.equals("address"));
            if (!supported) {
                String message = failure(source).getMessage();
                assertTrue(message.endsWith("Cannot convert " + from + " to " + target), message);
                continue;
            }
            JsonObject converted = execReturn(source);
            if (target.equals(from)) assertEquals(ExecOpcodes.VARIABLE, converted.get("c").getAsInt(), source);
            else {
                assertEquals(ExecOpcodes.CONVERT, converted.get("c").getAsInt(), source);
                assertEquals(targetCode(target), converted.get("type").getAsInt(), source);
                assertEquals(ExecOpcodes.VARIABLE, converted.getAsJsonObject("v").get("c").getAsInt(), source);
            }
            roundTrip(source);
        }
        // Content/range errors belong to execution, including malformed literal strings.
        for (String source : List.of("int main(){return int(2147483647.9);}", "int main(){return int(\"+7\");}",
                "float main(){return float(16777217);}", "int main(){return int(\" 1\");}",
                "double main(){return double(\"1e400\");}")) roundTrip(source);
    }

    @Test void anyAlwaysEmitsConversionWhileKnownIdentityKeepsOneEvaluation() throws Exception {
        for (String target : List.of("int", "float", "double", "string", "address")) {
            String source = target + " main(value){return " + target + "(value);}";
            JsonObject converted = execReturn(source);
            assertEquals(ExecOpcodes.CONVERT, converted.get("c").getAsInt());
            assertEquals(targetCode(target), converted.get("type").getAsInt());
            roundTrip(source);
        }
        JsonObject identity = execReturn("int main(){return int(getDepth());}");
        assertEquals(1, identity.get("t").getAsInt());
        assertEquals(0x0abd0001, identity.get("id").getAsInt());
        roundTrip("void main(){int(1);float(1);double(1);string(1);address(alloc(1));}");
        roundTrip("int main(){int x=1;for(int(x);true;int(x)){break;}return x;}");
    }

    @Test void referencesNullAndTypeParametersCannotUseScalarConversions() {
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case("class P{int n;}int main(){P value;return int(value);}", "Cannot convert P to int"),
                new Case("class P{int n;}address main(){P* p=null;return address(p);}", "Cannot convert P * to address"),
                new Case("class P{int n;}string main(){P* p=null;return string(p);}", "Cannot convert P * to string"),
                new Case("string main(){buffer<int> b;return string(b);}", "Cannot convert buffer<int> to string"),
                new Case("address main(){return address(null);}", "Cannot convert null to address"),
                new Case("<T> int convert(T value){return int(value);}void main(){}", "Cannot convert T to int"),
                new Case("int main(){return int(print(1));}", "Cannot convert void to int"))) {
            String message = failure(item.source()).getMessage();
            assertTrue(message.endsWith(item.message()), message);
        }
    }

    @Test void conversionSyntaxGivesTargetedDiagnosticsAndKeepsNamesReserved() throws Exception {
        for (String name : List.of("boolean", "bool")) {
            String message = failure("void main(){" + name + "(1);}").getMessage();
            assertTrue(message.endsWith("Boolean conversions are not supported; write a comparison"), message);
        }
        for (String source : List.of("int main(){double x=1.5;return (int)x;}",
                "int main(){return (int) -2.0;}", "class P{int n;}void main(){P* p=null;(P*)p;}",
                "int main(){return ((int)3.5);}")) {
            String message = failure(source).getMessage();
            assertTrue(message.contains("C-style casts are not supported; write int(x)"), message);
        }
        for (String name : SourceRules.CONVERSIONS) {
            assertTrue(Compiler.isBuiltinName(name), name);
            String message = failure("void " + name + "(){}void main(){}").getMessage();
            assertTrue(message.endsWith("'" + name + "' is a type name and cannot be declared as a name"), message);
        }
        for (String call : List.of("int()", "int(1,2)", "int<int>(1)")) {
            String message = failure("int main(){return " + call + ";}").getMessage();
            assertTrue(message.contains("expects 1 arguments") || message.contains("does not take type arguments"), message);
        }
        roundTrip("int main(){int n=1;return (int(n))+(n);}");
    }

    @Test void conversionChecksApplyToHandWrittenAstCalls() throws Exception {
        JsonObject ast = tree("int main(){return int(1);}");
        JsonObject call = main(ast).getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
        call.getAsJsonArray("param").set(0, new JsonPrimitive(true));
        String message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.endsWith("Cannot convert boolean to int"), message);
        call.addProperty("call", "bool");
        message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.endsWith("Boolean conversions are not supported; write a comparison"), message);
    }

    @Test void conversionWireHasAValidatedIntegerTargetAndOneOperand() throws Exception {
        for (String target : List.of("int", "float", "double", "string", "address")) {
            String source = target + " main(value){return " + target + "(value);}";
            AcsObject program = Compiler.compile(tree(source)); AbdValue wire = program.toValue();
            var returned = body(wire).getAsAss().values.get(1).getAsAss().values.get(0).getAsAss();
            var conversion = returned.values.get(2).getAsAss();
            assertEquals(3, conversion.values.size());
            assertEquals(ExecOpcodes.CONVERT, AbdBasicType.abd2int(conversion.values.get(0)));
            assertEquals(targetCode(target), AbdBasicType.abd2int(conversion.values.get(1)));
            AbdValue truncated = raw(conversion.values.subList(0, 2));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, truncated)));
            conversion.values.add(conversion.values.get(2)); AbdValue overlong = conversion.toAbd();
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, overlong)));
        }
        AcsObject program = Compiler.compile(tree("int main(value){return int(value);}"));
        AbdValue wire = program.toValue();
        var returned = body(wire).getAsAss().values.get(1).getAsAss().values.get(0).getAsAss();
        var conversion = returned.values.get(2).getAsAss();
        for (int bad : List.of(-1, 4, 5, 6, 8, 100)) {
            AbdValue invalid = raw(List.of(conversion.values.get(0), AbdBasicType.int2Abd(bad), conversion.values.get(2)));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, invalid)));
        }
        AbdValue shortType = raw(List.of(conversion.values.get(0), new AbdValue(new byte[]{0}), conversion.values.get(2)));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, shortType)));
        AcsObject converted = ((AcsObject) ((AcsObject) program.getAsAcsArray("f").acsa.get(0))
                .getAsAcsArray("script").acsa.get(0)).getAsAcsObject("r");
        converted.put("type", 4);
        assertThrows(IllegalArgumentException.class, program::toValue);
        converted.put("type", "int");
        assertThrows(IllegalArgumentException.class, program::toValue);
        converted.mmp.remove("type");
        assertThrows(IllegalArgumentException.class, program::toValue);
    }

    private static AbdValue raw(List<AbdValue> values) { AbdSimpleStack stack = new AbdSimpleStack(); stack.values.addAll(values); return stack.toAbd(); }
    private static AbdValue body(AbdValue wire) { return wire.getAsAss().values.get(7).getAsAss().values.get(0).getAsAss().values.get(5); }
    private static AbdValue replaceBody(AbdValue wire, AbdValue body) {
        var root = wire.getAsAss(); var functions = root.values.get(7).getAsAss(); var function = functions.values.get(0).getAsAss();
        function.values.set(5, body); functions.values.set(0, function.toAbd()); root.values.set(7, functions.toAbd()); return root.toAbd();
    }
    @Test void appendedBinaryOpcodeWireRecordsHaveExactlyTwoOperands() throws Exception {
        for (String expression : List.of("7&3", "7|3", "7^3", "7<<3", "7>>3", "7>>>3")) {
            AbdValue wire = Compiler.compile(tree("int main(){return " + expression + ";}")).toValue();
            var returned = body(wire).getAsAss().values.get(1).getAsAss().values.get(0).getAsAss();
            var binary = returned.values.get(2).getAsAss();
            int opcode = AbdBasicType.abd2int(binary.values.get(0));
            assertTrue(opcode >= 43 && opcode <= 48);
            assertEquals(3, binary.values.size());
            assertEquals(opcode, ExecOpcodes.code(ExecOpcodes.name(opcode)));
            AbdValue truncated = raw(binary.values.subList(0, 2));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, truncated)));
            binary.values.add(binary.values.get(2)); AbdValue overlong = binary.toAbd();
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, overlong)));
        }
    }
}
