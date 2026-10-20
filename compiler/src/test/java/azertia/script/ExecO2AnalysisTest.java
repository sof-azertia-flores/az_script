package azertia.script;

import azertia.Main;
import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.ExecOpcodes.*;

class ExecO2AnalysisTest {
    static AcsObject compile(String source, Compiler.Optimization level) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source);
        return Compiler.compile(parser.toObj(),new Diagnostics(),true,level);
    }
    static AcsObject first(AcsObject program) { return (AcsObject)program.getAsAcsArray("f").acsa.get(0); }
    @Test void levelsAndOverridesDoNotExpandO1() {
        assertEquals(4,Compiler.optimizationPasses(Compiler.Optimization.STANDARD).size());
        assertEquals(Compiler.Optimization.ADVANCED,
                Main.parseCompileArguments(List.of("compile","x.azs","-O1","-O2")).optimization());
        assertEquals(Compiler.Optimization.STANDARD,
                Main.parseCompileArguments(List.of("compile","-O2","x.azs","-O1")).optimization());
        assertEquals(Compiler.Optimization.NONE,
                Main.parseCompileArguments(List.of("compile-json","x.json","-O2","-O0")).optimization());
        assertTrue(Main.parseCompileArguments(List.of("compile","x.azs","-O2","--no-fold","--no-propagation",
                "--no-inline","--no-loop-motion","--no-cfg-opt","--no-cse","--no-dce")).optimizationPasses().isEmpty());
    }
    @Test void effectsExcludeObserversAliasesAndRecursion() throws Exception {
        var program=compile("int leaf(int x){return x+1;}int caller(int x){return leaf(x);}"
                +"int observer(){return getDepth();}int recurse(int x){return recurse(x);}"
                +"int global=1;int readGlobal(){return global;}int main(){return caller(3);}",Compiler.Optimization.NONE);
        var functions=program.getAsAcsArray("f");var safe=ExecScalarAnalysis.safeFunctions(functions);
        int count=0;
        for(var item:functions.acsa) {
            var function=(AcsObject)item;
            if(safe.contains(function.getAsInt("id")))count++;
        }
        assertEquals(3,count);
    }
    @Test void loopsConvergeAndMeetKeepsOnlyProvenConstants() throws Exception {
        var function=first(compile("int run(int n){int a=7;int i=0;while(i<n){i+=1;}return a;}",Compiler.Optimization.NONE));
        var analysis=new ExecScalarAnalysis(function,Map.of());
        var returned=analysis.blocks.stream().filter(b->b.source!=null&&ExecScalarAnalysis.opcode(b.source)==RETURN).findFirst().orElseThrow();
        var value=analysis.evaluate(ExecScalarAnalysis.field(returned.source,"r"),returned.in);
        assertTrue(value.constant());assertEquals(7,value.low());
        assertTrue(returned.dominators.get(analysis.entry.id));
        assertFalse(analysis.entry.liveIn.isEmpty());
        var branched=first(compile("int run(boolean b){int a=0;if(b){a=3;}else{a=5;}return a;}",Compiler.Optimization.NONE));
        var second=new ExecScalarAnalysis(branched,Map.of());
        var end=second.blocks.stream().filter(b->b.source!=null&&ExecScalarAnalysis.opcode(b.source)==RETURN).findFirst().orElseThrow();
        var interval=second.evaluate(ExecScalarAnalysis.field(end.source,"r"),end.in);
        assertEquals(3,interval.low());assertEquals(5,interval.high());
    }
    @Test void intervalsDoNotMakeOverflowOrNegativeBitPatternsSafe() throws Exception {
        var function=first(compile("int run(int x){return x+1;}",Compiler.Optimization.NONE));
        var analysis=new ExecScalarAnalysis(function,Map.of());
        var returned=analysis.blocks.stream().filter(b->b.source!=null&&ExecScalarAnalysis.opcode(b.source)==RETURN).findFirst().orElseThrow();
        assertFalse(analysis.evaluate(ExecScalarAnalysis.field(returned.source,"r"),returned.in).total());
        var bit=first(compile("int run(int x,int y){return x&y;}",Compiler.Optimization.NONE));
        var bits=new ExecScalarAnalysis(bit,Map.of());
        var ret=bits.blocks.stream().filter(b->b.source!=null&&ExecScalarAnalysis.opcode(b.source)==RETURN).findFirst().orElseThrow();
        var range=bits.evaluate(ExecScalarAnalysis.field(ret.source,"r"),ret.in);
        assertEquals(Integer.MIN_VALUE,range.low());assertTrue(range.total());
        assertEquals(INT_VALUE,range.type());
    }
}
