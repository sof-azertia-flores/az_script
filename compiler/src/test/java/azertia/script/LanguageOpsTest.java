package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Language operators, control flow and exec records stay consistent through compile-json. */
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

    @Test void conditionalPrecedenceAssociativityAndAssignmentArmsArePreserved() throws Exception {
        JsonObject logical = returned("true || false ? 1 : 2");
        assertEquals("select", logical.get("call").getAsString());
        assertEquals("or", operand(logical, 0).get("call").getAsString());
        assertEquals("select", operand(returned("true?1:false?2:3"), 2).get("call").getAsString());
        assertEquals("select", operand(returned("true?false?1:2:3"), 1).get("call").getAsString());
        roundTrip("int main(){int x=0;int y=0;return true?x=1:y=2;}");
        roundTrip("int main(){int x=0;boolean c=true;x=c?1:2;return x;}");
        roundTrip("int select(int value){return value;}int main(){return true?select(1):select(2);}");
        String deep = "int main(){return " + "true?1:".repeat(700) + "1;}";
        String error = failure(deep).getMessage();
        assertTrue(error.contains("nesting"), error);
    }

    @Test void colonIdsKeepRawHexWhileConditionalArmsReadFullLiterals() throws Exception {
        for (String suffix : List.of("0002", "0010", "1e5", "000a")) {
            String source = "int value():" + suffix + "{return 7;}int main(){return value();}";
            JsonObject ast = tree(source);
            int id = Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("value").getAsString(), 16);
            assertEquals(Integer.parseInt(suffix, 16), id & 0xffff, suffix);
            roundTrip(source);
        }
        for (String suffix : List.of("12340001", "4d410008", "1e999")) {
            String source = "extern int value():" + suffix + ";int main(){return value();}";
            JsonObject ast = tree(source);
            assertEquals(Integer.parseUnsignedInt(suffix, 16),
                    Integer.parseUnsignedInt(ast.getAsJsonObject("abstract").get("value").getAsString(), 16));
            roundTrip(source);
        }
        assertEquals(10, returned("true?1:0010").getAsJsonArray("param").get(2).getAsInt());
        assertEquals(100000.0, returned("true?1:1e5").getAsJsonArray("param").get(2).getAsDouble());
        for (String source : List.of("double main(){return true?1:1.5;}",
                "double main(){return false?1:2e+3;}", "float main(){return false?1:2f;}",
                "boolean main(){return false?false:true;}",
                "class Base{int x;Base(int n){x=n;}}class Child:Base{Child():Base(1){}}int main(){Child value;return value.x;}"))
            roundTrip(source);
        for (String literal : List.of("1e999", "1e-999")) {
            String error = failure("double main(){return true?1:" + literal + ";}").getMessage();
            assertTrue(error.contains("Floating literal is outside double range"), error);
        }
        String suffix = failure("int main(){return true?1:000a;}").getMessage();
        assertTrue(suffix.contains("Invalid numeric suffix"), suffix);
        String afterId = failure("extern int value():1e999;\nint main(){return missing;}").getMessage();
        assertTrue(afterId.startsWith("<source>:2:"), afterId);
    }

    @Test void conditionalArmsPromoteNumericsAndKeepNominalTypes() throws Exception {
        JsonObject selected = execReturn("double main(){return true?1:2.0;}");
        assertEquals(ExecOpcodes.SELECT, selected.get("c").getAsInt());
        assertEquals(ExecOpcodes.CONVERT, selected.getAsJsonObject("then").get("c").getAsInt());
        assertEquals(3, selected.getAsJsonObject("then").get("type").getAsInt());
        assertEquals(2.0, selected.get("else").getAsDouble());
        selected = execReturn("float main(){return true?1.0f:2;}");
        assertEquals(ExecOpcodes.CONVERT, selected.getAsJsonObject("else").get("c").getAsInt());
        assertEquals(2, selected.getAsJsonObject("else").get("type").getAsInt());
        String classes = "class A{int x;}class B:A{int y;}";
        for (String source : List.of(classes + "A* pick(boolean c,A* a,B* b){return c?a:b;}void main(){}",
                classes + "A* pick(boolean c,A* a){return c?a:null;}void main(){}",
                classes + "<T extends A*> T pick(boolean c,T a){return c?a:null;}void main(){}",
                classes + "<T extends A*> T pick(boolean c,T a){return c?null:a;}void main(){}",
                "class P{int x;}P main(boolean c){P a;P b;return c?a:b;}",
                "buffer<int> main(boolean c){buffer<int> a;buffer<int> b;return c?a:b;}",
                "address main(boolean c,address a){return c?null:a;}",
                "int main(c){return c?1:2;}")) roundTrip(source);
        String incompatible = failure("int main(){return true?1:\"x\";}").getMessage();
        assertTrue(incompatible.contains("Conditional expression arms have incompatible types: int and string"), incompatible);
        String condition = failure("int main(){return 1?2:3;}").getMessage();
        assertTrue(condition.contains("conditional condition must be boolean, got int"), condition);
        String absent = failure("void sink(){}int main(){return true?1:sink();}").getMessage();
        assertTrue(absent.contains("Conditional expression arms cannot use void values"), absent);
    }

    @Test void conditionalReceiverCannotMakeAnIndexGetterWritable() throws Exception {
        for (String receiver : List.of("c?values[0]:value", "c?value:values[0]", "c?value:(c?values[0]:value)")) {
            for (String source : List.of(
                    "class P{int x;}class Values{P item;P operator[](int i){return item;}}"
                            + "void main(){Values values;P value;boolean c=true;(" + receiver + ").x=1;}",
                    "class P{int x;}void main(){buffer<P> values;P value;boolean c=true;("
                            + receiver.replace("values[0]", "values.get(0)") + ").x=1;}")) {
                String error = failure(source).getMessage();
                assertTrue(error.contains("Cannot modify a value returned by an index getter"), error);
            }
        }
        roundTrip("class P{int x;}void main(){P a;P b;boolean c=true;(c?a:b).x=1;}");
    }

    @Test void conditionalChecksAstShapeAndAssignmentConditionWarnings() throws Exception {
        JsonObject ast = tree("int main(){boolean c=true;return (c=false)?1:2;}");
        JsonObject selected = main(ast).getAsJsonArray("script").get(1).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
        Diagnostics quiet = new Diagnostics(); Compiler.compile(ast, quiet); assertTrue(quiet.warnings().isEmpty());
        operand(selected, 0).remove("_parenthesized");
        Diagnostics warns = new Diagnostics(); Compiler.compile(ast, warns);
        assertEquals(1, warns.warnings().size()); assertEquals("assign-in-condition", warns.warnings().get(0).code());
        selected.getAsJsonArray("param").remove(2);
        String error = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(error.contains("select needs exactly three operands"), error);
    }

    @Test void conditionalWireRequiresAllThreeExpressionFields() throws Exception {
        AcsObject program = Compiler.compile(tree("int main(){return true?1:2;}")); AbdValue wire = program.toValue();
        var returned = body(wire).getAsAss().values.get(1).getAsAss().values.get(0).getAsAss();
        var selected = returned.values.get(2).getAsAss(); assertEquals(4, selected.values.size());
        assertEquals(ExecOpcodes.SELECT, AbdBasicType.abd2int(selected.values.get(0)));
        AbdValue shortRecord = raw(selected.values.subList(0, 3));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, shortRecord)));
        selected.values.add(selected.values.get(3)); AbdValue longRecord = selected.toAbd();
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, longRecord)));
        AcsObject selectedView = ((AcsObject) ((AcsObject) program.getAsAcsArray("f").acsa.get(0))
                .getAsAcsArray("script").acsa.get(0)).getAsAcsObject("r");
        selectedView.mmp.remove("else"); assertThrows(IllegalArgumentException.class, program::toValue);
        roundTrip("double main(boolean c){return c?1:2.0;}");
    }

    private static JsonObject switchStatement(JsonObject ast) {
        return main(ast).getAsJsonArray("script").get(0).getAsJsonObject();
    }
    private static JsonArray switchSections(JsonObject ast) {
        return switchStatement(ast).getAsJsonArray("param").get(1).getAsJsonArray();
    }
    private static int opcodeCount(JsonElement value, int opcode) {
        int count = 0;
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) count += opcodeCount(child, opcode);
        else if (value.isJsonObject()) {
            JsonObject node = value.getAsJsonObject();
            if (node.has("t") && node.has("c") && node.get("c").getAsInt() == opcode) count++;
            for (JsonElement child : node.asMap().values()) count += opcodeCount(child, opcode);
        }
        return count;
    }
    private static int opcodeDepth(JsonElement value, int opcode) {
        int depth = 0;
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) depth = Math.max(depth, opcodeDepth(child, opcode));
        else if (value.isJsonObject()) {
            JsonObject node = value.getAsJsonObject();
            for (JsonElement child : node.asMap().values()) depth = Math.max(depth, opcodeDepth(child, opcode));
            if (node.has("t") && node.has("c") && node.get("c").getAsInt() == opcode) depth++;
        }
        return depth;
    }

    @Test void switchAstPreservesEachLabelAndItsOwnPosition() throws Exception {
        String source = """
                int main(int value){
                    switch(value){
                        case -1:
                        case 0:return 10;
                        default:return 20;
                        case 2:return 30;
                    }
                }
                """;
        JsonObject ast = tree(source), selected = switchStatement(ast);
        assertEquals("ctrl", selected.get("t").getAsString());
        assertEquals("switch", selected.get("call").getAsString());
        assertEquals(2, selected.getAsJsonArray("param").size());
        JsonArray sections = switchSections(ast);
        assertEquals(4, sections.size());
        for (int i = 0; i < sections.size(); i++) {
            JsonObject section = sections.get(i).getAsJsonObject();
            assertEquals("ctrl", section.get("t").getAsString());
            assertEquals("case", section.get("call").getAsString());
            assertEquals(i + 3, section.get("_line").getAsInt());
            assertEquals(1, section.getAsJsonArray("param").size());
        }
        assertEquals(-1, sections.get(0).getAsJsonObject().getAsJsonArray("labels").get(0).getAsInt());
        assertTrue(sections.get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonArray().isEmpty());
        assertEquals(1, sections.get(1).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonArray().size());
        JsonObject fallback = sections.get(2).getAsJsonObject();
        assertTrue(fallback.get("default").getAsBoolean());
        assertTrue(fallback.getAsJsonArray("labels").isEmpty());
        assertEquals(2, sections.get(3).getAsJsonObject().getAsJsonArray("labels").get(0).getAsInt());
        assertFalse(Compiler.canCompleteNormally(selected));
        roundTrip(source);
        String strings = "int main(string value){switch(value){case \"a\\0b\":return 1;default:return 0;}}";
        JsonObject label = switchSections(tree(strings)).get(0).getAsJsonObject();
        assertEquals("a" + (char) 0 + "b", label.getAsJsonArray("labels").get(0).getAsString());
        roundTrip(strings);
    }

    @Test void switchSectionsHaveIndependentScopesAndFinalImplicitBreaks() throws Exception {
        for (String source : List.of(
                "int main(int value){switch(value){case 0:int local=1;return local;default:int local=2;return local;case 1:int local=3;return local;}}",
                "int main(int value){int result=0;switch(value){case 0:result=1;break;default:result=2;}return result;}",
                "int main(int value){switch(value){}return 0;}",
                "int main(int value){switch(value){case 0:default:case 1:return 1;}}")) roundTrip(source);
        String escaped = failure("void main(int value){switch(value){case 0:int local=1;break;default:local=2;}}").getMessage();
        assertTrue(escaped.contains("Unknown variable: local"), escaped);
    }

    @Test void switchRejectsUnsupportedValuesAndNonliteralOrMismatchedLabels() {
        record Case(String source, String message) {}
        for (Case item : List.of(
                new Case("void main(){switch(true){default:break;}}", "switch value must be int or string, got boolean"),
                new Case("void main(){switch(1.0){default:break;}}", "switch value must be int or string, got double"),
                new Case("void main(value){switch(value){default:break;}}", "switch value must be int or string, got any; convert it, e.g. switch (int(value))"),
                new Case("void main(){switch(0){case 1+1:break;}}", "case label must be an int literal"),
                new Case("void main(int value){switch(value){case value:break;}}", "case label must be an int literal"),
                new Case("void main(){switch(0){case true:break;}}", "case label must be an int literal"),
                new Case("void main(){switch(0){case 1.0:break;}}", "case label must be an int literal"),
                new Case("void main(){switch(0){case \"x\":break;}}", "case label must be an int literal"),
                new Case("void main(){switch(\"x\"){case 1:break;}}", "case label must be a string literal"),
                new Case("void main(){switch(\"x\"){case \"x\"+\"y\":break;}}", "case label must be a string literal"),
                new Case("void main(){switch(0){case 2147483648:break;}}", "case label is outside signed 32-bit range"))) {
            String message = failure(item.source()).getMessage();
            assertTrue(message.contains(item.message()), message);
            assertFalse(message.contains("Malformed script JSON"), message);
        }
    }

    @Test void switchDuplicatesAreReportedAtTheSecondLabel() {
        for (String source : List.of(
                "void main(){\nswitch(0){\ncase -1:break;\ncase 0:break;\ncase -1:break;\n}}",
                "void main(){\nswitch(0){\ndefault:break;\ncase 0:break;\ndefault:break;\n}}")) {
            CompileException error = failure(source);
            assertEquals(5, error.errors().get(0).position().line(), error.getMessage());
            assertTrue(error.getMessage().contains(source.contains("case -1") ? "Duplicate case label -1" : "Duplicate default label"), error.getMessage());
        }
        String strings = failure("void main(){switch(\"x\"){case \"same\":break;case \"same\":break;}}").getMessage();
        assertTrue(strings.contains("Duplicate case label \"same\""), strings);
    }

    @Test void nonfinalSwitchSectionsMustTerminateAndNestedLoopBreaksStayLocal() throws Exception {
        for (String source : List.of(
                "void main(int value){switch(value){case 0:int local=1;case 1:break;}}",
                "void main(int value,boolean c){switch(value){case 0:if(c){break;}case 1:break;}}",
                "void main(int value){switch(value){case 0:while(true){break;}case 1:break;}}",
                "void main(int value){switch(value){case 0:switch(value){default:break;}case 1:break;}}")) {
            String message = failure(source).getMessage();
            assertTrue(message.contains("Switch section can fall through; end it with break, continue or return"), message);
        }
        String source = "int main(int value){switch(value){case 0:while(true){break;}return 1;default:return 2;}}";
        assertFalse(Compiler.canCompleteNormally(switchStatement(tree(source))));
        roundTrip(source);
    }

    @Test void switchReturnAnalysisRequiresACompleteReturningFallback() throws Exception {
        for (String source : List.of(
                "int main(int value){switch(value){case 0:return 1;default:return 2;}}",
                "int main(int value){switch(value){case 0:case 1:return 1;default:switch(value){case 2:return 2;default:return 3;}}}",
                "int main(){while(true){switch(0){case 0:break;}}}")) roundTrip(source);
        for (String source : List.of(
                "int main(int value){switch(value){case 0:return 1;}}",
                "int main(int value){switch(value){case 0:return 1;default:break;}}",
                "int main(int value){switch(value){case 0:return 1;default:while(true){break;}}}")) {
            String message = failure(source).getMessage();
            assertTrue(message.contains("non-void function can reach the end of its body without returning a value"), message);
        }
    }

    @Test void nestedSwitchContinuesReachTheEnclosingRealLoop() throws Exception {
        for (String source : List.of(
                "int main(){int result=0;for(int i=0;i<3;i+=1){switch(i){case 0:switch(i){default:continue;}default:break;}result+=1;}return result;}",
                "int main(){int i=0;while(i<3){i+=1;switch(i){case 1:continue;case 2:switch(i){default:continue;}default:break;}}return i;}",
                "int main(){while(true){switch(0){default:return 1;continue;}}}",
                "void main(int value){switch(value){case 0:while(true){break;}break;default:for(int i=0;i<2;i+=1){continue;}break;}}")) roundTrip(source);
        String source = "void main(){\nswitch(0){\ndefault:switch(0){\ndefault:continue;\n}}}";
        CompileException error = failure(source);
        assertTrue(error.getMessage().contains("continue can only be used inside a loop"), error.getMessage());
        assertEquals(4, error.errors().get(0).position().line(), error.getMessage());
    }

    @Test void largeGroupedSwitchLabelsProduceBalancedConditionsAndLeaveTheAstReadOnly() throws Exception {
        StringBuilder source = new StringBuilder("int main(int value){switch(value){");
        for (int label = 0; label < 64; label++) source.append("case ").append(label).append(':');
        source.append("return 1;default:return 0;}}");
        JsonObject ast = tree(source.toString()); String original = ast.toString();
        assertEquals(65, switchSections(ast).size());
        for (boolean debug : List.of(false, true)) {
            JsonElement program = Compiler.compile(ast, debug).toJson();
            assertEquals(64, opcodeCount(program, ExecOpcodes.EQUAL));
            assertEquals(63, opcodeCount(program, ExecOpcodes.OR));
            assertEquals(6, opcodeDepth(program, ExecOpcodes.OR));
            assertEquals(original, ast.toString());
        }
        roundTrip(source.toString());
        assertEquals(original, ast.toString());
    }

    @Test void switchKeywordsAndLabelPlacementHaveTargetedSourceErrors() {
        for (String keyword : List.of("switch", "case", "default")) assertTrue(SourceRules.RESERVED_WORDS.contains(keyword), keyword);
        for (String keyword : List.of("case", "default")) {
            String message = failure("void main(){" + keyword + (keyword.equals("case") ? " 0" : "") + ":break;}").getMessage();
            assertTrue(message.contains("'" + keyword + "' can only be used directly inside a switch"), message);
        }
        for (String prefix : List.of("int value=1;", ";")) {
            String message = failure("void main(){switch(0){" + prefix + "case 0:break;}}").getMessage();
            assertTrue(message.contains("before its first case or default label"), message);
        }
        String expression = failure("int main(){return switch(0){default:return 1;};}").getMessage();
        assertTrue(expression.contains("cannot be used in an expression"), expression);
    }

    @Test void compileJsonValidatesSwitchSectionShapeAndCasePlacement() throws Exception {
        record Mutation(String message, Consumer<JsonObject> change) {}
        List<Mutation> mutations = List.of(
                new Mutation("switch needs a value and a section array", ast -> switchStatement(ast).getAsJsonArray("param").remove(1)),
                new Mutation("expected an array of switch sections", ast -> switchStatement(ast).getAsJsonArray("param").set(1, new JsonPrimitive(0))),
                new Mutation("expected a ctrl/case switch section", ast -> switchSections(ast).set(0, new JsonArray())),
                new Mutation("expected a ctrl/case switch section", ast -> switchSections(ast).get(0).getAsJsonObject().addProperty("call", "if")),
                new Mutation("case needs exactly one body array", ast -> switchSections(ast).get(0).getAsJsonObject().getAsJsonArray("param").remove(0)),
                new Mutation("expected a case body array", ast -> switchSections(ast).get(0).getAsJsonObject().getAsJsonArray("param").set(0, new JsonPrimitive(0))),
                new Mutation("required field is missing", ast -> switchSections(ast).get(0).getAsJsonObject().remove("labels")),
                new Mutation("expected an array", ast -> switchSections(ast).get(0).getAsJsonObject().addProperty("labels", 0)),
                new Mutation("expected true", ast -> switchSections(ast).get(1).getAsJsonObject().addProperty("default", false)),
                new Mutation("a case section needs a label or default:true", ast -> switchSections(ast).get(0).getAsJsonObject().getAsJsonArray("labels").remove(0)),
                new Mutation("case can only appear as a direct switch section", ast -> main(ast).getAsJsonArray("script").set(0, switchSections(ast).get(0).deepCopy())),
                new Mutation("case can only appear as a direct switch section", ast -> switchSections(ast).get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonArray().add(switchSections(ast).get(1).deepCopy())));
        for (Mutation mutation : mutations) {
            JsonObject ast = tree("void main(int value){switch(value){case 0:break;default:break;}}");
            mutation.change().accept(ast);
            String message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
            assertTrue(message.contains("Malformed script JSON") && message.contains(mutation.message()), message);
        }
        JsonObject ast = tree("void main(){switch(0){case 1:break;}}");
        switchSections(ast).get(0).getAsJsonObject().getAsJsonArray("labels").set(0, returned("1+1"));
        String message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
        assertTrue(message.contains("case label must be an int literal"), message);
        assertFalse(message.contains("Malformed script JSON"), message);
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
