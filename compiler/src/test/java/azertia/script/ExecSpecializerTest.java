package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecSpecializerTest {
    private static final String WORK = "int work(int x,boolean fast,int mode){if(fast){return x+1;}int s=0;int i=0;"
            + "while(i<x){if(mode==1){s+=i*2;}else{if(mode==2){s+=i*3;}else{s+=i;}}i++;}return s;}";
    private static AcsObject compile(String source) throws Exception {
        var passes = EnumSet.copyOf(Compiler.optimizationPasses(Compiler.Optimization.ADVANCED));
        passes.remove(SCALAR_INLINING); passes.add(SPECIALIZATION);
        return ExecO2OptimizerTest.compile(source, passes);
    }
    private static List<AcsObject> functions(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast).collect(Collectors.toList());
    }
    private static List<AcsObject> clones(AcsObject program) {
        return functions(program).stream().filter(f -> f.getAsInt("entry-kind") == 2).collect(Collectors.toList());
    }

    @Test void constantArgumentsGetSmallerPrivateClonesShownAsTheOriginal() throws Exception {
        var program = compile(WORK + "int main(){return work(5,true,0)+work(20,false,2)+work(9,false,2);}");
        var clones = clones(program);
        assertEquals(3, clones.size(), "one clone per distinct constant vector");
        assertEquals(Set.of(0x0fffffff, 0x0ffffffe, 0x0ffffffd), clones.stream().map(f -> f.getAsInt("id")).collect(Collectors.toSet()));
        for (var clone : clones) {
            var debug = clone.getAsAcsObject("debug");
            assertEquals("work", debug.getAsString("name"));
            assertEquals(0x0fff0001, debug.getAsInt("display-id"));
            assertTrue(ExecO2OptimizerTest.count(clone.mmp.get("script"), IF) < 3, "constant conditions are folded");
        }
        assertDoesNotThrow(program::toValue);
    }
    @Test void clonesThatShrinkTooLittleAreDropped() throws Exception {
        // Only y is constant: the body keeps every operation.
        var program = compile("int f(int x,int y){int s=x*y;s=s+x;s=s*y;return s-y;}int run(int a){return f(a,4);}int main(){return run(3);}");
        assertTrue(clones(program).isEmpty());
    }
    @Test void versionsPerFunctionAreBounded() throws Exception {
        var calls = IntStream.range(0, 8).mapToObj(i -> "work(" + i + ",true,0)").collect(Collectors.joining("+"));
        var program = compile(WORK + "int main(){return " + calls + ";}");
        assertEquals(ExecSpecializer.PER_FUNCTION, clones(program).size());
    }
    @Test void fixedGenericContextsGetClonesWithoutHiddenContexts() throws Exception {
        var program = compile("<T> T pick(boolean c,T a,T b){if(c){return a;}return b;}<T> int count(T x,int n){return n+1;}"
                + "int main(){int x=pick(true,3,4);boolean y=pick(false,true,false);int z=count(5,10)+count(true,3);return y?0:x+z;}");
        var clones = clones(program);
        assertEquals(3, clones.size(), "two pick versions; one count for every context, as it names none");
        for (var clone : clones) assertEquals(0, clone.getAsInt("hidden-count"));
        assertTrue(clones.stream().anyMatch(f -> f.getAsInt("return-type") == Compiler.AsTypes.BOOLEAN_VALUE));
        assertDoesNotThrow(program::toValue);
    }
    @Test void specializationCanBeDisabled() throws Exception {
        var args = azertia.Main.parseCompileArguments(List.of("compile", "x.azs", "-O3", "--no-specialize"));
        assertFalse(args.optimizationPasses().contains(SPECIALIZATION));
        assertTrue(Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE).contains(SPECIALIZATION));
        assertFalse(Compiler.optimizationPasses(Compiler.Optimization.ADVANCED).contains(SPECIALIZATION));
    }
}
