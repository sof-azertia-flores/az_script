package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class DepthLimitsTest {
    private static final String LIMIT = "azscript.internal.abdLimit";
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    private record Compiled(JsonObject ast, JsonObject program) {}

    private static <T> T atLimit(String limit, CheckedSupplier<T> action) throws Exception {
        String previous = System.getProperty(LIMIT);
        try { System.setProperty(LIMIT, limit); return action.get(); }
        finally { if (previous == null) System.clearProperty(LIMIT); else System.setProperty(LIMIT, previous); }
    }
    private static JsonObject tree(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source); return parser.toObj();
    }
    private static Compiled compile(String source, int limit) throws Exception {
        return atLimit(Integer.toString(limit), () -> {
            JsonObject ast = tree(source), restored = JsonParser.parseString(ast.toString()).getAsJsonObject();
            String original = ast.toString(); JsonObject result = null;
            for (boolean debug : List.of(false, true)) {
                AcsObject program = Compiler.compile(ast, debug);
                for (AcsElement item : program.getAsAcsArray("f").acsa)
                    ExecShape.requireFits(((AcsObject) item).mmp.get("script"), 4, limit);
                byte[] wire = program.toValue().toAbdFormat();
                assertArrayEquals(wire, Compiler.compile(restored, debug).toValue().toAbdFormat(), source);
                assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat(), source);
                assertEquals(original, ast.toString());
                JsonObject view = program.toJson().getAsJsonObject();
                assertTrue(nodes(view, ExecShape.INTERNAL_SEQUENCE).isEmpty());
                if (!debug) result = view;
            }
            return new Compiled(ast, result);
        });
    }
    private static JsonObject main(Compiled result) {
        return result.program().getAsJsonArray("f").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(function -> function.get("id").getAsInt() == 0x0fff0000).findFirst().orElseThrow();
    }
    private static int id(Compiled result, String name) {
        return Integer.parseUnsignedInt(result.ast().getAsJsonObject("abstract").get(name).getAsString(), 16);
    }
    private static List<JsonObject> nodes(JsonElement value, int opcode) {
        List<JsonObject> result = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) result.addAll(nodes(child, opcode));
        else if (value.isJsonObject()) {
            JsonObject node = value.getAsJsonObject();
            if (node.has("t") && node.has("c") && node.get("t").getAsInt() == 0 && node.get("c").getAsInt() == opcode) result.add(node);
            for (JsonElement child : node.asMap().values()) result.addAll(nodes(child, opcode));
        }
        return result;
    }
    private static List<JsonObject> calls(JsonElement value, int id) {
        List<JsonObject> result = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) result.addAll(calls(child, id));
        else if (value.isJsonObject()) {
            JsonObject node = value.getAsJsonObject();
            if (node.has("t") && node.has("id") && node.get("t").getAsInt() == 1 && node.get("id").getAsInt() == id) result.add(node);
            for (JsonElement child : node.asMap().values()) result.addAll(calls(child, id));
        }
        return result;
    }
    private static int index(JsonArray statements, Predicate<JsonElement> predicate) {
        for (int i = 0; i < statements.size(); i++) if (predicate.test(statements.get(i))) return i;
        return -1;
    }
    private static String sum(int terms) { return "1+".repeat(terms - 1) + "1"; }
    private static String nestedCalls(int count, String value) { return "id(".repeat(count) + value + ")".repeat(count); }
    private static CompileException failure(String source, int limit) throws Exception {
        return atLimit(Integer.toString(limit), () -> {
            CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(tree(source)), source);
            assertFalse(error.internal(), error::getMessage); return error;
        });
    }

    @Test void eagerStatementsCallsAndTypedMembersFitEveryForcedWireLimit() throws Exception {
        for (int limit : List.of(96, 64, 48, 32)) {
            Compiled arithmetic = compile("int main(int seed){int local=seed;return local+(" + sum(131) + ");}", limit);
            assertTrue(main(arithmetic).get("local-count").getAsInt() > 1, "the long expression must actually spill");
            compile("int id(int value){return value;}int main(){return " + nestedCalls(70, "1") + ";}", limit);
            compile("class P{P* next;int value;}int main(P* value){return value" + ".next".repeat(70) + ".value;}", limit);
        }
    }

    @Test void eagerCapturesRetainLeftToRightCallsAndReadsBeforeMutation() throws Exception {
        String source = "int mark(int value){return value;}int id(int value){return value;}int pair(int a,int b){return a+b;}"
                + "int main(){return pair(mark(1)," + nestedCalls(24, "mark(2)") + ");}";
        Compiled result = compile(source, 32); JsonArray body = main(result).getAsJsonArray("script"); int marker = id(result, "mark");
        assertEquals(2, calls(main(result), marker).size());
        int left = index(body, statement -> calls(statement, marker).stream().anyMatch(call -> call.getAsJsonArray("param").get(0).getAsInt() == 1));
        int right = index(body, statement -> calls(statement, marker).stream().anyMatch(call -> call.getAsJsonArray("param").get(0).getAsInt() == 2));
        assertTrue(left >= 0 && left < right, "the first call must finish before evaluating the deep second argument");
        source = "int shared=1;int mutate(){shared=9;return 2;}int id(int value){return value;}"
                + "int main(){return shared+" + nestedCalls(24, "mutate()") + ";}";
        Compiled mutation = compile(source, 32); JsonArray statements = main(mutation).getAsJsonArray("script");
        int read = index(statements, statement -> nodes(statement, ExecOpcodes.DEFINE).stream().anyMatch(definition ->
                definition.has("val") && definition.get("val").isJsonObject()
                        && definition.getAsJsonObject("val").has("c") && definition.getAsJsonObject("val").get("c").getAsInt() == ExecOpcodes.VARIABLE
                        && definition.getAsJsonObject("val").get("v").getAsInt() < 0));
        int mutate = index(statements, statement -> !calls(statement, id(mutation, "mutate")).isEmpty());
        assertTrue(read >= 0 && read < mutate, "a mutable global read cannot move after the call");
    }

    @Test void rawMoveTargetsAreEvaluatedOnceAndTypedReadsKeepTheirObjectAddressMarker() throws Exception {
        Compiled raw = compile("address target(){return alloc(1);}void main(){mem_get(target())=" + sum(70) + ";}", 32);
        JsonObject function = main(raw); assertEquals(1, calls(function, id(raw, "target")).size());
        JsonObject move = nodes(function, ExecOpcodes.MOVE).stream()
                .filter(node -> node.get("v1").isJsonObject() && node.getAsJsonObject("v1").has("id")).findFirst().orElseThrow();
        JsonObject target = move.getAsJsonObject("v1");
        assertEquals(0x0abd0006, target.get("id").getAsInt());
        assertEquals(ExecOpcodes.VARIABLE, target.getAsJsonArray("param").get(0).getAsJsonObject().get("c").getAsInt());
        Compiled typed = compile("class P{P* next;int value;}int main(P* value){return value" + ".next".repeat(70) + ".value+(" + sum(70) + ");}", 32);
        List<JsonObject> reads = calls(typed.program(), 0x0abd0006); assertFalse(reads.isEmpty());
        for (JsonObject read : reads) {
            assertEquals(1, read.getAsJsonArray("param").size());
            assertEquals(ExecOpcodes.OBJECT_ADDRESS, read.getAsJsonArray("param").get(0).getAsJsonObject().get("c").getAsInt(),
                    "typed access must never become a raw mem_get of a captured address");
        }
    }

    @Test void memoryAssignmentsKeepTargetValidationBeforeASpilledPureRhs() throws Exception {
        String effect = "int effect(){return 1;}";
        for (String source : List.of(
                effect + "void main(address target){mem_get(target)=" + sum(70) + "+effect();}",
                "class P{int first;int second;}" + effect
                        + "void main(P* target){target.second=" + sum(70) + "+effect();}")) {
            String message = failure(source, 32).getMessage();
            assertTrue(message.contains("store RHS in a local variable first"), message);
        }
        Compiled typed = compile("class P{int first;int second;}P* target(P* value){return value;}"
                + "void main(P* value){target(value).second=" + sum(70) + ";}", 32);
        JsonObject function = main(typed);
        assertEquals(1, calls(function, id(typed, "target")).size(), "the target receiver must be evaluated once");
        JsonObject move = nodes(function, ExecOpcodes.MOVE).stream()
                .filter(node -> !calls(node.get("v1"), 0x0abd0006).isEmpty()).findFirst().orElseThrow();
        JsonObject address = move.getAsJsonObject("v1").getAsJsonArray("param").get(0).getAsJsonObject();
        assertEquals(ExecOpcodes.OBJECT_ADDRESS, address.get("c").getAsInt());
        assertEquals(1, address.get("offset").getAsInt(), "the second field keeps its offset from the object base");
        assertEquals(ExecOpcodes.VARIABLE, address.getAsJsonObject("v").get("c").getAsInt());
        JsonArray body = function.getAsJsonArray("script");
        int validation = index(body, statement -> statement.isJsonObject()
                && statement.getAsJsonObject().has("id")
                && statement.getAsJsonObject().get("id").getAsInt() == 0x0abd0006);
        int arithmetic = index(body, statement -> !nodes(statement, ExecOpcodes.ADD).isEmpty());
        assertTrue(validation >= 0 && validation < arithmetic, "invalid targets must fail before evaluating the spilled RHS");
    }

    @Test void lazyOperandsSelectArmsAndWhileConditionsCannotBeHoisted() throws Exception {
        for (String expression : List.of("false&&(" + sum(70) + "==70)", "true||(" + sum(70) + "==70)",
                "true?(" + sum(70) + "):0", "false?0:(" + sum(70) + ")")) {
            String type = expression.contains("?") ? "int" : "boolean";
            String message = failure(type + " main(){return " + expression + ";}", 32).getMessage();
            assertTrue(message.contains("store this operand in a local variable first"), message);
        }
        String loop = failure("void main(){while(" + sum(70) + "==70){break;}}", 32).getMessage();
        assertTrue(loop.contains("store this condition in a local variable first"), loop);
        atLimit("32", () -> {
            JsonObject ast = tree("int main(){return 0;}");
            JsonObject function = ast.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000");
            JsonObject returned = function.getAsJsonArray("script").get(0).getAsJsonObject();
            returned.getAsJsonArray("param").set(0, GeneraterJson.getExpression("if(true," + sum(70) + ",0);", 0, new GeneraterJson.AzScript()).generate());
            String message = assertThrows(CompileException.class, () -> Compiler.compile(ast)).getMessage();
            assertTrue(message.contains("store this operand in a local variable first"), message); return null;
        });
    }

    @Test void owningArgumentCapturesAreDroppedBeforeTheNextStatementOrReturn() throws Exception {
        String declarations = "class P{int value;P(int n){value=n;}~P(){}}"
                + "P make(int n){P value(n);return value;}int pick(P value,int n){return value.value+n;}";
        Compiled statement = compile(declarations + "void main(){pick(make(1)," + sum(70) + ");print(99);}", 32);
        JsonArray body = main(statement).getAsJsonArray("script");
        int invoke = index(body, node -> !calls(node, id(statement, "pick")).isEmpty());
        int drop = index(body, node -> !nodes(node, ExecOpcodes.DROP).isEmpty());
        int next = index(body, node -> !calls(node, 0x0abd0000).isEmpty());
        assertTrue(invoke >= 0 && drop >= invoke && next > drop, "owning captures end with their source statement");
        Compiled returned = compile(declarations + "int main(){return pick(make(1)," + sum(70) + ");}", 32);
        body = main(returned).getAsJsonArray("script");
        int release = index(body, node -> !nodes(node, ExecOpcodes.DROP).isEmpty());
        int finish = index(body, node -> !nodes(node, ExecOpcodes.RETURN).isEmpty());
        assertTrue(release >= 0 && finish > release, "return values are saved before dropping owning captures");
        String owned = "class P{int value;P(int n){value=n;}~P(){}}P make(int n){P value(n);return value;}"
                + "P choose(P value,int n){return value;}P main(){return choose(make(1)," + sum(70) + ");}";
        Compiled moved = compile(owned, 32);
        assertFalse(nodes(main(moved), ExecOpcodes.DROP).isEmpty());
        JsonObject result = nodes(main(moved), ExecOpcodes.RETURN_OBJECT).get(0);
        assertEquals(ExecOpcodes.MOVE_OUT, result.getAsJsonObject("r").get("c").getAsInt());
    }

    @Test void statementIfReleasesConditionReceiversBeforeEnteringAReturningBranch() throws Exception {
        String source = "class Flag{boolean ready;Flag(boolean value){ready=value;}~Flag(){}}"
                + "Flag makeValue(){Flag value(true);return value;}boolean id(boolean value){return value;}"
                + "int main(){if(" + nestedCalls(24, "makeValue().ready") + "){return 1;}return 0;}";
        Compiled compiled = compile(source, 32); JsonObject function = main(compiled);
        assertEquals(1, calls(function, id(compiled, "makeValue")).size());
        JsonArray body = function.getAsJsonArray("script");
        int branchIndex = index(body, statement -> !nodes(statement, ExecOpcodes.IF).isEmpty());
        assertTrue(branchIndex >= 0);
        JsonObject branch = nodes(body.get(branchIndex), ExecOpcodes.IF).get(0);
        assertFalse(nodes(branch.get("val"), ExecOpcodes.RETURN).isEmpty());
        JsonObject condition = branch.getAsJsonObject("v");
        assertEquals(ExecOpcodes.VARIABLE, condition.get("c").getAsInt());
        int conditionSlot = condition.get("v").getAsInt();
        int checked = index(body, statement -> nodes(statement, ExecOpcodes.DEFINE).stream()
                .anyMatch(definition -> definition.get("v").getAsInt() == conditionSlot));
        int release = index(body, statement -> !nodes(statement, ExecOpcodes.DROP).isEmpty());
        assertTrue(checked >= 0 && checked < release && release < branchIndex,
                "the boolean condition is saved and its owned receivers are dropped before either branch runs");
    }

    @Test void borrowingNamedValueReceiversAndPointerAliasesDoesNotMoveTheirOwners() throws Exception {
        Compiled value = compile("class P{int value;~P(){}}int main(P owner){return owner.value+(" + sum(70) + ");}", 32);
        JsonObject function = main(value);
        assertTrue(nodes(function, ExecOpcodes.BLOCK_ADDRESS).stream().anyMatch(address ->
                address.get("v").isJsonObject() && address.getAsJsonObject("v").get("c").getAsInt() == ExecOpcodes.VARIABLE
                        && address.getAsJsonObject("v").get("v").getAsInt() == 0));
        assertTrue(nodes(function, ExecOpcodes.MOVE_OUT).stream().noneMatch(move -> move.get("v").getAsInt() == 0));
        assertTrue(nodes(function, ExecOpcodes.DEFINE).stream().noneMatch(definition ->
                definition.has("val") && definition.get("val").isJsonObject()
                        && definition.getAsJsonObject("val").has("c")
                        && definition.getAsJsonObject("val").get("c").getAsInt() == ExecOpcodes.VARIABLE
                        && definition.getAsJsonObject("val").get("v").getAsInt() == 0),
                "borrowing the named value's address must not copy its owner into a capture");
        Compiled pointer = compile("class P{int value;}int use(address pointer,int value){return value;}"
                + "int main(P* source){P* alias=source;return use(address(alias),alias.value+(" + sum(70) + "));}", 32);
        assertTrue(nodes(main(pointer), ExecOpcodes.MOVE_OUT).stream().noneMatch(move ->
                move.get("v").getAsInt() == 0 || move.get("v").getAsInt() == 1));
    }

    @Test void ifConditionsAndProtectedForGuardsAndStepsSpillAtTheirExecutionSite() throws Exception {
        compile("void main(){if(" + sum(70) + "==70){print(1);}}", 32);
        String source = "class P{int value;P(int n){value=n;}~P(){}}P make(int n){P value(n);return value;}"
                + "int main(){for(int i=0;make(" + sum(40) + "+i).value<100;i=make(" + sum(40) + "+i).value){if(i>90){break;}}return 0;}";
        Compiled compiled = compile(source, 32);
        JsonObject loop = nodes(main(compiled), ExecOpcodes.WHILE).get(0);
        assertTrue(loop.get("v").getAsBoolean(), "for conditions remain inside their iteration block");
        assertTrue(nodes(loop.get("val"), ExecOpcodes.DEFINE).size() >= 2, "guard and step captures must run per iteration");
        assertEquals(2, calls(loop.get("val"), id(compiled, "make")).size());
    }

    @Test void retainedLiteralReceiversAllowPureTailsAndRejectLaterAutomaticPointers() throws Exception {
        String value = "class V{int n=7;~V(){}}V makeValue(){V value;return value;}";
        Compiled pure = compile(value + "int main(){int n=1;return makeValue().n+" + "n+".repeat(69) + "n;}",32);
        assertEquals(1,calls(main(pure),id(pure,"makeValue")).size());
        assertFalse(nodes(main(pure),ExecOpcodes.DROP).isEmpty());
        String unsafe = value + "class P{int n;~P(){}}P* makePointer(){P* value();return value;}"
                + "int fail(P* value){return 1/0;}int main(){int n=1;return makeValue().n+"
                + "n+".repeat(69) + "n+fail(makePointer());}";
        String message=failure(unsafe,32).getMessage();
        assertTrue(message.contains("this cut would change object lifetime or copying"),message);
        assertTrue(message.contains("store this operand in a local variable first"),message);
    }

    @Test void uncertainBorrowedAnyCannotGainACopyBeforeAnOperatorTypeError() {
        FunctionTables tables=new FunctionTables(Map.of(),null);tables.slots.put(0,6);tables.nextSlot=1;
        AcsElement sum=new AcsIntegerElement(1);
        for(int i=1;i<70;i++){AcsObject add=new AcsObject();add.put("t",0);add.put("c",ExecOpcodes.ADD);add.put("v1",sum);add.put("v2",new AcsIntegerElement(1));sum=add;}
        AcsObject borrowed=new AcsObject();borrowed.put("t",0);borrowed.put("c",ExecOpcodes.VARIABLE);borrowed.put("v",0);
        AcsObject add=new AcsObject();add.put("t",0);add.put("c",ExecOpcodes.ADD);add.put("v1",borrowed);add.put("v2",sum);
        AcsObject returned=new AcsObject();returned.put("t",0);returned.put("c",ExecOpcodes.RETURN);returned.put("r",add);
        AcsArray body=new AcsArray();body.acsa.add(returned);
        String message=assertThrows(AbdLinearizer.LimitExceeded.class,()->new AbdLinearizer(tables,32).function(body)).getMessage();
        assertTrue(message.contains("this cut would change object lifetime or copying"),message);
    }

    @Test void retainedOwnersRejectTransitiveScalarScopeTransfers() throws Exception {
        String declarations="address saved=null;class V{int n=7;~V(){print(mem_get(saved));}}"
                +"V makeValue(){V value;return value;}int transfer(){saved=alloc(1);mem_get(saved)=7;mem_send_up(saved);return 1/0;}"
                +"int helper(){return transfer();}";
        String source=declarations+"int main(){int n=1;return makeValue().n+"+"n+".repeat(69)+"n+helper();}";
        String message=failure(source,32).getMessage();
        assertTrue(message.contains("this cut would change object lifetime or copying"),message);
    }

    @Test void borrowedBufferReceiversCannotBeCopiedToCrossDeepArguments() throws Exception {
        String message=failure("void main(){buffer<int> values;values.resize("+sum(70)+");}",32).getMessage();
        assertTrue(message.contains("this cut would change object lifetime or copying"),message);
    }

    @Test void assignmentCapturesKeepOwnedResultMetadataAtMaterializingCallEdges() throws Exception {
        Compiled result=compile("class P{int n;~P(){}}int pick(P value,int n){return n;}"
                +"int main(){P first;P second;return pick((first=second),"+sum(70)+");}",32);
        JsonObject function=main(result);
        JsonObject definition=nodes(function,ExecOpcodes.DEFINE).stream().filter(node->node.has("val")
                &&node.get("val").isJsonObject()&&node.getAsJsonObject("val").has("c")
                &&node.getAsJsonObject("val").get("c").getAsInt()==ExecOpcodes.SET).findFirst().orElseThrow();
        int slot=definition.get("v").getAsInt();
        assertTrue(nodes(function,ExecOpcodes.MOVE_OUT).stream().anyMatch(node->node.get("v").getAsInt()==slot));
        assertTrue(nodes(function,ExecOpcodes.DROP).stream().anyMatch(node->
                node.getAsJsonObject("v").get("v").getAsInt()==slot));
    }

    @Test void standaloneExpressionsRejectRequiredCapturesAndValidateTheirAstFirst() throws Exception {
        atLimit("32", () -> {
            JsonElement small = GeneraterJson.getExpression("1+2", 0, new GeneraterJson.AzScript()).generate();
            AcsElement simple = Compiler.compileExpression(small, new Compiler.LogicEnvironment()); ExecShape.requireFits(simple, 1, 32);
            JsonElement deep = GeneraterJson.getExpression(sum(70), 0, new GeneraterJson.AzScript()).generate();
            String message = assertThrows(IllegalArgumentException.class,
                    () -> Compiler.compileExpression(deep, new Compiler.LogicEnvironment())).getMessage();
            assertTrue(message.contains("Expression requires statement-level spilling; store this operand in a local variable first"), message);
            JsonArray block = new JsonArray(); block.add(deep);
            AcsElement lowered = Compiler.compileExpression(block, new Compiler.LogicEnvironment());
            assertInstanceOf(AcsArray.class, lowered); ExecShape.requireFits(lowered, 1, 32);
            assertTrue(nodes(lowered.toJson(), ExecShape.INTERNAL_SEQUENCE).isEmpty());
            JsonArray cycle = new JsonArray(); cycle.add(cycle);
            assertThrows(IllegalArgumentException.class, () -> Compiler.compileExpression(cycle, new Compiler.LogicEnvironment()));
            return null;
        });
        assertEquals(320, TreeLimits.MAX_NESTING);
        JsonElement nested = new JsonPrimitive(1);
        for (int i = 0; i <= TreeLimits.MAX_NESTING; i++) { JsonArray array = new JsonArray(); array.add(nested); nested = array; }
        JsonElement excessive = nested;
        String ast = assertThrows(IllegalArgumentException.class,
                () -> Compiler.compileExpression(excessive, new Compiler.LogicEnvironment())).getMessage();
        assertTrue(ast.contains("AST nesting exceeds 320 levels"), ast);
        String source = failure("boolean main(){\nreturn " + "!".repeat(700) + "true;}", 128).getMessage();
        assertTrue(source.contains("nesting") && source.contains("<source>:2:"), source);
        compile("int main(){return 1;}", 32);
    }

    @Test void anUnspillableScalarAtAnExcessiveStatementLevelGetsAUserDiagnostic() throws Exception {
        String source="void main(){"+"{".repeat(20)+"print(1);"+"}".repeat(20)+"}";
        String message=failure(source,32).getMessage();
        assertTrue(message.contains("nesting exceeds the ABD limit of 32 levels"),message);
        assertTrue(message.contains("<source>:1:"),message);
    }

    @Test void testOnlyAbdLimitRejectsMalformedOrOutOfRangeValuesAndRestoresState() throws Exception {
        String previous = System.getProperty(LIMIT);
        for (String value : List.of("", "31", "129", "not-an-integer", "32.0", "999999999999999999999999")) {
            atLimit(value, () -> {
                String message = assertThrows(IllegalArgumentException.class, () -> Compiler.compile(tree("void main(){}"))).getMessage();
                assertTrue(message.contains("azscript.internal.abdLimit must be an integer between 32 and 128"), message);
                return null;
            });
            assertEquals(previous, System.getProperty(LIMIT));
        }
        compile("void main(){}", 32); compile("void main(){}", 128);
        assertEquals(previous, System.getProperty(LIMIT));
    }

    private static JsonObject returnedAst(JsonObject ast) {
        return ast.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0000")
                .getAsJsonArray("script").get(0).getAsJsonObject().getAsJsonArray("param").get(0).getAsJsonObject();
    }
    private static JsonElement legacySumAst(int operands, int prefixLength) {
        JsonElement left = new JsonPrimitive(1);
        for (int i = 1; i < operands; i++) {
            JsonObject node = new JsonObject(); node.addProperty("t", "ctrl"); node.addProperty("call", "add");
            JsonArray params = new JsonArray(); params.add(left); params.add(1); node.add("param", params);
            node.addProperty("_line", 1); node.addProperty("_column", prefixLength + 2 * i);
            left = node;
        }
        return left;
    }

    @Test void flatChainsStartAbove128OperandsAndPreserveLegacyAstAndOperatorPositions() throws Exception {
        String prefix = "int main(){return ";
        JsonObject old = returnedAst(tree(prefix + sum(128) + ";}"));
        assertEquals(legacySumAst(128, prefix.length()).toString(), old.toString(), "the legacy AST is byte-identical through 128 operands");
        for (int count : List.of(129, 10_000)) {
            JsonObject ast = tree(prefix + sum(count) + ";}"); JsonObject chain = returnedAst(ast);
            assertEquals("chain", chain.get("call").getAsString());
            assertEquals(count, chain.getAsJsonArray("param").size());
            JsonArray operators = chain.getAsJsonArray("operators"); assertEquals(count - 1, operators.size());
            for (int i = 0; i < operators.size(); i++) {
                JsonObject operator = operators.get(i).getAsJsonObject();
                assertEquals(Set.of("call", "_line", "_column"), operator.keySet());
                assertEquals("add", operator.get("call").getAsString());
                assertFalse(operator.has("_file")); assertEquals(1, operator.get("_line").getAsInt());
                assertEquals(prefix.length() + 2 * (i + 1), operator.get("_column").getAsInt());
            }
            assertEquals(operators.get(operators.size() - 1).getAsJsonObject().get("_column"), chain.get("_column"));
            assertTrue(AstShape.validate(ast).isEmpty());
            assertEquals(ast, JsonParser.parseString(ast.toString()));
        }
        compile(prefix + sum(129) + ";}", 32);
    }

    @Test void flatChainsKeepParenthesesPrecedenceAndEveryCanonicalOperatorName() throws Exception {
        StringBuilder expression = new StringBuilder("(1+2)+3*4");
        for (int i = 2; i < 129; i++) expression.append(i % 2 == 0 ? "+1" : "-1");
        JsonObject chain = returnedAst(tree("int main(){return " + expression + ";}"));
        JsonArray operands = chain.getAsJsonArray("param"), operators = chain.getAsJsonArray("operators");
        assertEquals(129, operands.size());
        assertEquals("add", operands.get(0).getAsJsonObject().get("call").getAsString());
        assertEquals(JsonParser.parseString("[1,2]"), operands.get(0).getAsJsonObject().getAsJsonArray("param"));
        assertEquals("multiply", operands.get(1).getAsJsonObject().get("call").getAsString());
        for (int i = 0; i < operators.size(); i++)
            assertEquals(i == 0 || i % 2 == 1 ? "add" : "minus", operators.get(i).getAsJsonObject().get("call").getAsString());
        String[][] spellings = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="},
                {"<", ">", "<=", ">="}, {"<<", ">>", ">>>"}, {"+", "-"}, {"*", "/", "%"}};
        String[][] names = {{"or"}, {"and"}, {"bit_or"}, {"bit_xor"}, {"bit_and"}, {"cmp", "ne"},
                {"lower", "greater", "le", "ge"}, {"shl", "shr", "ushr"}, {"add", "minus"}, {"multiply", "divide", "mod"}};
        for (int group = 0; group < spellings.length; group++) {
            String literal = group < 2 ? "true" : "1"; StringBuilder source = new StringBuilder(literal);
            for (int i = 0; i < 128; i++) source.append(spellings[group][i % spellings[group].length]).append(literal);
            JsonObject parsed = GeneraterJson.getExpression(source.toString(), 0, new GeneraterJson.AzScript()).generate().getAsJsonObject();
            assertEquals("chain", parsed.get("call").getAsString());
            for (int i = 0; i < 128; i++) assertEquals(names[group][i % names[group].length],
                    parsed.getAsJsonArray("operators").get(i).getAsJsonObject().get("call").getAsString());
        }
        compile("int chain(int left,int right){return left+right;}int main(){return chain(1,2);}", 32);
    }

    @Test void flatBooleanAndCheckedStringChainsRemainShallowAndMixedAdditionKeepsItsLeftFold() throws Exception {
        for (String operator : List.of("&&", "||")) {
            Compiled logical = compile("boolean mark(int n){return true;}boolean main(){return "
                    + (operator.equals("&&") ? "false" : "true") + (operator + "mark(1)").repeat(2_000) + ";}", 32);
            assertEquals(2_000, calls(main(logical), id(logical, "mark")).size());
            assertEquals(0, main(logical).get("local-count").getAsInt(), "a balanced lazy chain needs no eager captures");
        }
        Compiled literals = compile("string main(){return " + "\"x\"+".repeat(9_999) + "\"x\";}", 32);
        assertEquals(0, main(literals).get("local-count").getAsInt());
        Compiled checked = compile("string piece(){return \"x\";}string main(string value){return value"
                + "+piece()+value".repeat(1_000) + ";}", 32);
        assertEquals(1_000, calls(main(checked), id(checked, "piece")).size());
        assertEquals(0, main(checked).get("local-count").getAsInt());
        Compiled mixed = compile("string main(){return 1+2+\"x\"" + "+1".repeat(126) + ";}", 32);
        assertTrue(nodes(main(mixed), ExecOpcodes.ADD).stream().anyMatch(add -> {
            if (!add.get("v1").isJsonObject() || !add.get("v2").isJsonPrimitive() || !add.getAsJsonPrimitive("v2").isString()) return false;
            JsonObject first = add.getAsJsonObject("v1");
            return first.has("c") && first.get("c").getAsInt() == ExecOpcodes.ADD
                    && first.get("v1").equals(new JsonPrimitive(1)) && first.get("v2").equals(new JsonPrimitive(2))
                    && add.get("v2").getAsString().equals("x");
        }), "1+2 must complete numerically before the chain reaches its first string");
    }

    @Test void flatChainSchemaRejectsWrongCountsOperatorsAndPositionsBeforeCompilation() throws Exception {
        JsonObject original = tree("int main(){return " + sum(129) + ";}"); String unchanged = original.toString();
        Map<String,java.util.function.Consumer<JsonObject>> mutations = new LinkedHashMap<>();
        mutations.put("missing operands", node -> node.remove("param"));
        mutations.put("operand type", node -> node.addProperty("param", 1));
        mutations.put("threshold", node -> node.getAsJsonArray("param").remove(128));
        mutations.put("missing operators", node -> node.remove("operators"));
        mutations.put("operator list type", node -> node.addProperty("operators", "add"));
        mutations.put("operator count", node -> node.getAsJsonArray("operators").remove(127));
        mutations.put("operator object", node -> node.getAsJsonArray("operators").set(0, new JsonPrimitive("add")));
        mutations.put("missing call", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().remove("call"));
        mutations.put("call type", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("call", true));
        mutations.put("canonical call", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("call", "+"));
        mutations.put("nonbinary call", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("call", "select"));
        mutations.put("mixed precedence", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("call", "multiply"));
        mutations.put("line", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("_line", 0));
        mutations.put("column", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("_column", "1"));
        mutations.put("file", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().addProperty("_file", 1));
        mutations.put("expression metadata", node -> node.getAsJsonArray("operators").get(0).getAsJsonObject().add("param", new JsonArray()));
        for (var mutation : mutations.entrySet()) {
            JsonObject changed = JsonParser.parseString(unchanged).getAsJsonObject(); mutation.getValue().accept(returnedAst(changed));
            List<String> problems = AstShape.validate(changed); assertFalse(problems.isEmpty(), mutation.getKey());
            assertTrue(problems.stream().allMatch(problem -> problem.contains("/_funcfff0000/script/0/param/0/")), problems::toString);
            CompileException error = assertThrows(CompileException.class, () -> Compiler.compile(changed), mutation.getKey());
            assertFalse(error.internal(), error::getMessage);
        }
        JsonObject unpositioned = JsonParser.parseString(unchanged).getAsJsonObject();
        for (JsonElement operator : returnedAst(unpositioned).getAsJsonArray("operators"))
            for (String position : List.of("_file", "_line", "_column")) operator.getAsJsonObject().remove(position);
        assertTrue(AstShape.validate(unpositioned).isEmpty(), "handwritten operator positions remain optional");
        atLimit("32", () -> { Compiler.compile(unpositioned, true); return null; });
        assertEquals(unchanged, original.toString());
    }
}
