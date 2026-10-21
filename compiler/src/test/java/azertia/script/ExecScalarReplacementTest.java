package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecScalarReplacementTest {
    private static final String CELL = "class Cell{int value;Cell(int n){value=n;}int read(){return value;}}";
    private static AcsObject compile(String source) throws Exception {
        return ExecO2OptimizerTest.compile(source, Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
    }
    private static long count(AcsObject program, int opcode) {
        return program.getAsAcsArray("f").acsa.stream()
                .mapToLong(f -> ExecO2OptimizerTest.count(((AcsObject)f).mmp.get("script"), opcode)).sum();
    }

    @Test void fieldsAndMethodsBecomeLocals() throws Exception {
        var program = compile(CELL + "int run(int n){int t=0;for(int i=0;i<n;i++){Cell c(i%17);c.value=c.value+1;t=t+c.read();}return t;}"
                + "int main(){return run(40);}");
        assertEquals(1, count(program, RESERVE_BLOCK), "the literal id is still taken");
        assertEquals(1, count(program, NEW_BLOCK), "only the unused factory allocates");
        assertDoesNotThrow(program::toValue);
    }
    @Test void copiesReserveTheirOwnIds() throws Exception {
        var program = compile(CELL + "int run(int n){int t=0;for(int i=0;i<n;i++){Cell a(i%17);Cell b=a;b.value=b.value+1;"
                + "t=t+b.value+a.value;}return t;}int main(){return run(40);}");
        assertEquals(2, count(program, RESERVE_BLOCK));
        assertDoesNotThrow(program::toValue);
    }
    @Test void borrowedObjectsStayObjects() throws Exception {
        var program = compile(CELL + "int peek(Cell (*) p,int k){if(k>0){return peek(p,k-1);}return p.value;}"
                + "int run(int n){Cell a(n);Cell b(n+1);return a.read()+peek(b,2);}int main(){return run(4);}");
        assertEquals(1, count(program, RESERVE_BLOCK), "only a is replaced");
    }
    @Test void methodsThatComputeOrDispatchKeepTheObject() throws Exception {
        assertEquals(0, count(compile("class Cell{int value;Cell(int n){value=n;}int twice(){return value*2;}}"
                + "int run(int n){Cell c(n);return c.twice();}int main(){return run(4);}"), RESERVE_BLOCK));
        assertEquals(0, count(compile("class Cell{int value;Cell(int n){value=n;}virtual int read(){return value;}}"
                + "int run(int n){Cell c(n);return c.read();}int main(){return run(4);}"), RESERVE_BLOCK));
    }
    @Test void constructorsThatComputeKeepTheObject() throws Exception {
        // value=n*2 can overflow inside the constructor's frame: it is not inlined away.
        var program = compile("class Cell{int value;Cell(int n){value=n*2;}int read(){return value;}}"
                + "int run(int n){Cell c(n);return c.read();}int main(){return run(4);}");
        assertEquals(0, count(program, RESERVE_BLOCK));
    }
    @Test void scalarReplacementCanBeDisabled() throws Exception {
        var args = azertia.Main.parseCompileArguments(List.of("compile", "x.azs", "-O3", "--no-scalar-replacement"));
        assertFalse(args.optimizationPasses().contains(SCALAR_REPLACEMENT));
        assertTrue(Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE).contains(SCALAR_REPLACEMENT));
        assertFalse(Compiler.optimizationPasses(Compiler.Optimization.ADVANCED).contains(SCALAR_REPLACEMENT));
    }
}
