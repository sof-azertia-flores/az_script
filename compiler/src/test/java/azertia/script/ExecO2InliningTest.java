package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecO2InliningTest {
    private AcsObject compile(String source) throws Exception {
        return ExecO2OptimizerTest.compile(source,Set.of(SCALAR_INLINING));
    }
    private AcsObject main(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("id")==0x0fff0000).findFirst().orElseThrow();
    }
    @Test void snapshotsRepeatedArgumentsAndRenamesLocals() throws Exception {
        var program=compile("int square(int x){int y=x*x;return y+x;}int main(){int x=7;return square(x+1);}");
        var function=main(program);
        assertEquals(0,ExecO2OptimizerTest.count(function.mmp.get("script"),CALL));
        assertEquals(3,function.getAsInt("local-count"));
        assertEquals(2,program.getAsAcsArray("f").acsa.size(),"public callee must remain");
        assertEquals(1,ExecO2OptimizerTest.count(function.mmp.get("script"),MULTIPLY));
    }
    @Test void failingArithmeticRetainsCalleeLocation() throws Exception {
        var program=compile("int divide(int x){\n return 1/x;\n}\nint main(){\n return divide(0);\n}");
        var function=main(program);
        assertEquals(0,ExecO2OptimizerTest.count(function.mmp.get("script"),CALL));
        var locations=function.getAsAcsObject("debug").getAsAcsArray("locations").acsa;
        assertTrue(locations.stream().anyMatch(p->p.toJson().getAsString().split(":")[2].equals("2")));
    }
    @Test void doesNotMoveCallsOutOfLazyExpressionsOrConditions() throws Exception {
        var program=compile("boolean leaf(int x){return x>0;}int main(){if(false&&leaf(1)){return 3;}while(leaf(0)){}return 7;}");
        assertEquals(2,ExecO2OptimizerTest.count(main(program).mmp.get("script"),CALL));
    }
    @Test void observersRecursionAndUnknownArgumentsKeepCalls() throws Exception {
        for(String source:List.of(
                "extern int host():0x12340001;int f(int x){return host();}int main(){return f(3);}",
                "int f(int x){return f(x);}int main(){return f(3);}",
                "extern int host():0x12340001;int f(int x){return x*x;}int main(){return f(host());}")) {
            var program=compile(source);
            assertTrue(ExecO2OptimizerTest.count(main(program).mmp.get("script"),CALL)>0);
        }
    }
    @Test void expansionIsBoundedAndIndependentOfDeclarationOrder() throws Exception {
        String calls=String.join("",Collections.nCopies(50,"a=f(a);"));
        var function=main(compile("int f(int x){int a=x&7;int b=a|1;return b^x;}int main(){int a=1;"+calls+"return a;}"));
        assertTrue(ExecO2OptimizerTest.count(function.mmp.get("script"),CALL)>0);
        assertTrue(function.getAsInt("local-count")<=129);
        for(String source:List.of(
                "int a(int x){return b(x);}int b(int x){return c(x);}int c(int x){return x+1;}int main(){return a(1);}",
                "int c(int x){return x+1;}int b(int x){return c(x);}int a(int x){return b(x);}int main(){return a(1);}")) {
            assertEquals(1,ExecO2OptimizerTest.count(main(compile(source)).mmp.get("script"),CALL),
                    "a three-edge chain exceeds the two frozen expansion rounds");
        }
    }
}
