package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecUncheckedArithmeticTest {
    private static AcsObject compile(String source) throws Exception {
        // Without inlining and specialization each operation is counted once.
        var passes = EnumSet.copyOf(Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
        passes.removeAll(Set.of(SEQUENCE_INLINING, SPECIALIZATION));
        return ExecO2OptimizerTest.compile(source, passes);
    }
    private static long count(AcsObject program, int opcode) {
        return program.getAsAcsArray("f").acsa.stream()
                .mapToLong(f -> ExecO2OptimizerTest.count(((AcsObject)f).mmp.get("script"), opcode)).sum();
    }

    @Test void provenOperationsWrap() throws Exception {
        // i<n bounds i+1; the remainder bounds total*7 and the sum.
        var program = compile("int run(int n){int total=0;int i=0;while(i<n){total=(total*7+i%13)%10007;i=i+1;}return total;}"
                + "int main(){return run(512);}");
        assertEquals(3, count(program, WRAP_ADD) + count(program, WRAP_MULTIPLY));
        assertEquals(0, count(program, ADD) + count(program, MULTIPLY));
        assertDoesNotThrow(program::toValue);
    }
    @Test void unprovenOperationsKeepTheirCheck() throws Exception {
        var program = compile("int run(int a,int b){int s=a+b;int d=a-1;return s*d;}int main(){return run(3,4);}");
        assertEquals(0, count(program, WRAP_ADD) + count(program, WRAP_SUBTRACT) + count(program, WRAP_MULTIPLY));
    }
    @Test void rangesComeFromConditionsAndConstants() throws Exception {
        var program = compile("int run(int a){if(a>=0&&a<1000){return a*a-a;}return 0;}int main(){return run(30);}");
        assertEquals(1, count(program, WRAP_MULTIPLY));
        assertEquals(1, count(program, WRAP_SUBTRACT));
    }
    @Test void otherLevelsNeverWrap() throws Exception {
        var source = "int run(int n){int t=0;int i=0;while(i<n){t=t+i%7;i=i+1;}return t;}int main(){return run(9);}";
        var program = ExecO2OptimizerTest.compile(source, Compiler.optimizationPasses(Compiler.Optimization.ADVANCED));
        assertEquals(0, count(program, WRAP_ADD));
        var args = azertia.Main.parseCompileArguments(List.of("compile", "x.azs", "-O3", "--no-unchecked-arithmetic"));
        assertFalse(args.optimizationPasses().contains(UNCHECKED_ARITHMETIC));
        assertTrue(Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE).contains(UNCHECKED_ARITHMETIC));
    }
}
