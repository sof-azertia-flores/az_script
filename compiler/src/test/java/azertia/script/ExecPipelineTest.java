package azertia.script;

import azertia.Main;
import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;

class ExecPipelineTest {
    private AcsObject compile(String source, Set<Compiler.OptimizationPass> passes) throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute(source);
        return Compiler.compile(parser.toObj(),new Diagnostics(),true,passes);
    }
    private AcsObject main(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("id")==0x0fff0000).findFirst().orElseThrow();
    }
    private long count(AcsElement source,int opcode) {
        var node=ExecIr.read(source,null);long result=node.opcode()==opcode?1:0;
        for(var edge:node.children())result+=count(edge.value().source(),opcode);return result;
    }
    @Test void aggressiveLevelContainsTheExplicitAdvancedPasses() throws Exception {
        // O2 is a fixed list: passes added for O3 must not join it.
        assertEquals(EnumSet.of(Compiler.OptimizationPass.CONSTANTS,Compiler.OptimizationPass.PROPAGATION,
                Compiler.OptimizationPass.INLINING,Compiler.OptimizationPass.LOOP_MOTION,Compiler.OptimizationPass.CONTROL_FLOW,
                Compiler.OptimizationPass.COMMON_SUBEXPRESSIONS,Compiler.OptimizationPass.DEAD_CODE,
                Compiler.OptimizationPass.SCALAR_INLINING,Compiler.OptimizationPass.RANGE_LOOP_MOTION,
                Compiler.OptimizationPass.FLOW_PROPAGATION),Compiler.optimizationPasses(Compiler.Optimization.ADVANCED));
        var aggressive=EnumSet.copyOf(Compiler.optimizationPasses(Compiler.Optimization.ADVANCED));
        aggressive.remove(Compiler.OptimizationPass.SCALAR_INLINING);aggressive.add(Compiler.OptimizationPass.SEQUENCE_INLINING);
        aggressive.add(Compiler.OptimizationPass.SLOT_COMPACTION);
        assertEquals(aggressive,Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
        assertEquals(Compiler.Optimization.AGGRESSIVE,Main.parseCompileArguments(List.of("compile","x.azs","-O2","-O3")).optimization());
        assertEquals(Compiler.Optimization.ADVANCED,Main.parseCompileArguments(List.of("compile","x.azs","-O3","-O2")).optimization());
        var args=Main.parseCompileArguments(List.of("compile","x.azs","-O3","--no-inline"));
        assertFalse(args.optimizationPasses().contains(Compiler.OptimizationPass.INLINING));
        assertFalse(args.optimizationPasses().contains(Compiler.OptimizationPass.SCALAR_INLINING));
    }
    @Test void passesCanBeDisabledIndependently() throws Exception {
        var args=Main.parseCompileArguments(List.of("compile","x.azs","-O1","--no-fold","--no-propagation","--no-inline","--no-loop-motion"));
        assertTrue(args.optimizationPasses().isEmpty());
        assertEquals(Set.of(Compiler.OptimizationPass.CONSTANTS),Compiler.optimizationPasses(Compiler.Optimization.CONSTANTS));
        String source="int leaf(int x){return x;}int main(){return leaf(7);}";
        var plain=main(compile(source,Set.of())).mmp.get("script");
        var inline=main(compile(source,Set.of(Compiler.OptimizationPass.INLINING))).mmp.get("script");
        assertEquals(1,count(plain,CALL));assertEquals(0,count(inline,CALL));
    }
    @Test void inliningPreservesPotentialFailuresAndArgumentOrder() throws Exception {
        var passes=Set.of(Compiler.OptimizationPass.INLINING);
        for(var test:Map.of("return x+1;",2147483647,"return str_length(\"ab\");",2147483647,"return 1/x;",0).entrySet()) {
            var bodyMain=main(compile("int leaf(int x){"+test.getKey()+"}int main(){return leaf("+test.getValue()+");}",passes)).mmp.get("script");
            assertEquals(1,count(bodyMain,CALL));
        }
        var repeated=main(compile("int leaf(int x){return x&x;}int main(){return leaf(3);}",passes));
        assertEquals(1,count(repeated.mmp.get("script"),CALL),"arguments must not be duplicated");
        var reversed=main(compile("int leaf(int x,int y){return y&x;}int main(){return leaf(3,5);}",passes));
        assertEquals(1,count(reversed.mmp.get("script"),CALL),"arguments must not be reordered");
    }
    @Test void fixedScalarGenericIdentitySpecializesWithoutFunctionGrowth() throws Exception {
        String source="<T> T identity(T x){return x;}int main(){return identity<int>(7);}";
        var before=compile(source,Set.of());var after=compile(source,Set.of(Compiler.OptimizationPass.INLINING));
        assertEquals(before.getAsAcsArray("f").acsa.size(),after.getAsAcsArray("f").acsa.size());
        assertEquals(0,count(main(after).mmp.get("script"),CALL));
        assertEquals(0,count(main(after).mmp.get("script"),CHECK_TYPE));
    }
    @Test void loopMotionMovesOnlyTotalInvariantOperations() throws Exception {
        String source="int run(int n){int sum=0;for(int i=0;i<100;i++){sum+=(n&7);}return sum;}int main(){return run(11);}";
        var before=compile(source,Set.of());var after=compile(source,Set.of(Compiler.OptimizationPass.LOOP_MOTION));
        var first=(AcsObject)before.getAsAcsArray("f").acsa.get(0);var second=(AcsObject)after.getAsAcsArray("f").acsa.get(0);
        assertTrue(second.getAsInt("local-count")>first.getAsInt("local-count"));
        String trap="int main(){int n=0;int sum=0;while(false){sum+=1/n;}return sum;}";
        assertArrayEquals(compile(trap,Set.of()).toValue().toAbdFormat(),
                compile(trap,Set.of(Compiler.OptimizationPass.LOOP_MOTION)).toValue().toAbdFormat());
    }
    @Test void readableAstMayUseANonBlockFunctionBody() throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute("int leaf(){return 7;}int main(){return leaf();}");
        var ast=parser.toObj();
        for(var entry:ast.getAsJsonObject("body").getAsJsonObject("fff").entrySet()) {
            var function=entry.getValue().getAsJsonObject();
            function.add("script",function.getAsJsonArray("script").get(0));
        }
        var optimized=Compiler.compile(ast,new Diagnostics(),true,Compiler.Optimization.STANDARD);
        assertEquals(0,count(main(optimized).mmp.get("script"),CALL));
        assertDoesNotThrow(()->optimized.toValue().toAbdFormat());
    }
    @Test void foldedSpillsDoNotLeaveDeadCompilerTemporaries() throws Exception {
        String source="int main(){return "+String.join("+",Collections.nCopies(200,"1"))+";}";
        AcsElement before=main(compile(source,Set.of())).mmp.get("script");
        AcsElement after=main(compile(source,Set.of(Compiler.OptimizationPass.CONSTANTS,
                Compiler.OptimizationPass.PROPAGATION))).mmp.get("script");
        assertTrue(count(before,DEFINE)>0,"fixture must require lowered temporary slots");
        assertEquals(0,count(after,DEFINE));
        assertEquals(0,count(after,VARIABLE));
    }
    @Test void ownershipAnalysisRetainsIdentityAndCleanup() throws Exception {
        var program=compile("class A{int x;~A(){print(x);}}int main(){A a;return 1;}",Set.of());
        var body=main(program).mmp.get("script");
        var analysis=ExecLoopMotion.ownership(ExecIr.read(body,null));
        assertNotEquals(ExecLoopMotion.Escape.NONE,analysis.escape());
        assertTrue(analysis.scriptReentry());
        assertArrayEquals(program.toValue().toAbdFormat(),compile("class A{int x;~A(){print(x);}}int main(){A a;return 1;}",Set.of(Compiler.OptimizationPass.LOOP_MOTION)).toValue().toAbdFormat());
    }
}
