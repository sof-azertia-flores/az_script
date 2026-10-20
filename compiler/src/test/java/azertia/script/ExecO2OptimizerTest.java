package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecO2OptimizerTest {
    static AcsObject compile(String source, Set<Compiler.OptimizationPass> passes) throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute(source);
        return Compiler.compile(parser.toObj(),new Diagnostics(),true,passes);
    }
    static AcsElement body(AcsObject program) { return ExecO2AnalysisTest.first(program).mmp.get("script"); }
    static long count(AcsElement value,int opcode) {
        var node=ExecIr.read(value,null);
        return (node.opcode()==opcode?1:0)+node.children().stream().mapToLong(e->count(e.value().source(),opcode)).sum();
    }
    @Test void cfgUsesSuccessfulBranchFacts() throws Exception {
        var program=compile("int run(boolean b){int a=0;if(b){a=3;}else{a=3;}return a+4;}",
                Set.of(PROPAGATION,CONTROL_FLOW));
        var code=body(program);
        assertEquals(0,count(code,ADD));
        assertEquals(1,count(code,IF),"the unknown original condition must still run");
        var dead=body(compile("int run(int n){if(n<5){if(n>10){return 7;}return n;}return 0;}",
                Set.of(CONTROL_FLOW)));
        assertEquals(1,count(dead,IF),"the contradictory inner branch can be eliminated");
    }
    @Test void cseReusesOnlyAnAvailableValue() throws Exception {
        String source="int run(int n){int a=n*7;int b=n*7;return a+b;}";
        assertEquals(2,count(body(compile(source,Set.of())),MULTIPLY));
        assertEquals(1,count(body(compile(source,Set.of(COMMON_SUBEXPRESSIONS))),MULTIPLY));
        String overwritten="int run(int n){int a=n*7;n=4;int b=n*7;return a+b;}";
        assertEquals(2,count(body(compile(overwritten,Set.of(COMMON_SUBEXPRESSIONS))),MULTIPLY));
        String scope="int run(int n){{int a=n*7;}int b=n*7;return b;}";
        assertEquals(2,count(body(compile(scope,Set.of(COMMON_SUBEXPRESSIONS))),MULTIPLY));
        String overwrittenResult="int run(int n){int a=n*7;a=4;int b=n*7;return a+b;}";
        assertEquals(2,count(body(compile(overwrittenResult,Set.of(COMMON_SUBEXPRESSIONS))),MULTIPLY));
    }
    @Test void deadAssignmentsRetainTrapsAndLiveLoopValues() throws Exception {
        var safe=body(compile("int run(int n){int a=0;a=n&7;return n;}",Set.of(DEAD_CODE)));
        assertEquals(0,count(safe,SET));
        var trap=body(compile("int run(int n){int a=0;a=1/n;return n;}",Set.of(DEAD_CODE)));
        assertEquals(1,count(trap,DIVIDE));
        var loop=body(compile("int run(int n){int i=0;while(i<n){i+=1;}return i;}",Set.of(DEAD_CODE)));
        assertEquals(1,count(loop,SET));
    }
    @Test void dominatingExpressionsEnterBranchesButDoNotEscapeTheirScopes() throws Exception {
        var code=body(compile("int run(int n,boolean b){int a=n*7;if(b){return n*7;}else{return a;}}",
                Set.of(COMMON_SUBEXPRESSIONS)));
        assertEquals(1,count(code,MULTIPLY));
        var modified=body(compile("int run(int n,boolean b){int a=n*7;if(b){n=3;return n*7;}return a;}",
                Set.of(COMMON_SUBEXPRESSIONS)));
        assertEquals(2,count(modified,MULTIPLY));
    }
    @Test void loopConditionCseChecksEveryBackedgeWrite() throws Exception {
        var operand=body(compile("int f(int n){int a=7/n;while(7/n>0){n=0;}return a;}",
                Set.of(COMMON_SUBEXPRESSIONS)));
        assertEquals(2,count(operand,DIVIDE));
        var storage=body(compile("int f(int n){int a=n*7;int i=0;while(i<2&&n*7>0){a=0;i++;}return i;}",
                Set.of(COMMON_SUBEXPRESSIONS)));
        assertEquals(2,count(storage,MULTIPLY));
        var stable=body(compile("int f(int n){int a=7/n;int i=0;while(i<2&&7/n>0){int b=7/n;i++;}return a+i;}",
                Set.of(COMMON_SUBEXPRESSIONS)));
        assertEquals(1,count(stable,DIVIDE),"a previous successful, unchanged value dominates every iteration");
    }
    @Test void unusedDefinitionsRetainFailuresAndRequiredStorage() throws Exception {
        assertEquals(0,count(body(compile("int f(int n){int a=n&7;return n;}",Set.of(DEAD_CODE))),DEFINE));
        assertEquals(1,count(body(compile("int f(int n){int a=1/n;return n;}",Set.of(DEAD_CODE))),DEFINE));
        var code=body(compile("int f(int n){int a=0;a=n;return a;}",Set.of(DEAD_CODE)));
        assertEquals(1,count(code,DEFINE));
        assertEquals(1,count(code,SET));
    }
    @Test void pruningDoesNotSpeculateErrorsOrRemoveInfiniteLoops() throws Exception {
        var trap=body(compile("int run(int n){if((1/n)==0){return 1;}return 2;}",Set.of(CONTROL_FLOW)));
        assertEquals(1,count(trap,DIVIDE));
        var loop=body(compile("void run(){while(true){}}",Set.of(CONTROL_FLOW,DEAD_CODE)));
        assertEquals(1,count(loop,WHILE));
        var skipped=body(compile("int run(){while(false){return 1/0;}return 3;}",Set.of(CONTROL_FLOW)));
        assertEquals(0,count(skipped,WHILE));
    }
    @Test void observersAndObjectRegionsAreNotRewritten() throws Exception {
        for(String source:List.of("int run(){int a=1;int b=1;return getDepth();}",
                "class A{int x;~A(){print(x);}}int run(){A a;int n=1;return n+2;}",
                "<T> T identity(T x){return x;}int main(){buffer<int> b;b.push(identity<int>(7));return b.get(0);}")) {
            var before=compile(source,Set.of()).toValue().toAbdFormat();
            var after=compile(source,Set.of(CONTROL_FLOW,COMMON_SUBEXPRESSIONS,DEAD_CODE)).toValue().toAbdFormat();
            assertArrayEquals(before,after);
        }
    }
}
