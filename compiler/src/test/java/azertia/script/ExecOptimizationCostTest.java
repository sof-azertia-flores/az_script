package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecOptimizationCostTest {
    private static AcsObject program(String source) throws Exception {
        return ExecO3Test.compile(source,true,Set.of());
    }
    @Test void growingPastTheNativeBudgetRestoresTheWholeFunction() throws Exception {
        var program=program("int main(){return 1;}");var function=ExecO3Test.main(program);
        String original=function.toJson().toString();
        var large=program("int main(){int x=1;"+"x=x+1;".repeat(320)+"return x;}");
        assertTrue(ExecOptimizationCost.measure(ExecO3Test.main(large)).nativeOperations()>4096);
        ExecOptimizationCost.run(program,new IdentityHashMap<>(),128,()-> {
            function.put("script",ExecO3Test.main(large).mmp.get("script"));function.put("local-count",1);
            function.getAsAcsObject("debug").put("name","changed");
        });
        assertEquals(original,function.toJson().toString());
    }
    @Test void failedPrivateCloneCannotLeaveDanglingRewrittenCallers() throws Exception {
        var program=program("int main(){return 7;}");String original=program.toJson().toString();
        ExecOptimizationCost.run(program,new IdentityHashMap<>(),128,()-> {
            var clone=(AcsObject)ExecO2.copy(ExecO3Test.main(program),null);
            clone.put("id",0x0fffffff);clone.put("entry-kind",2);clone.put("local-count",257);
            program.getAsAcsArray("f").acsa.add(clone);
            ExecO3Test.main(program).put("script",new AcsArray());
        });
        assertEquals(original,program.toJson().toString());
    }
    @Test void budgetCountsParameterSlotsAndDoesNotRejectUnchangedLargeInputs() throws Exception {
        var program=program("int main(){int x=1;"+"x=x+1;".repeat(320)+"return x;}");
        String original=program.toJson().toString();
        ExecOptimizationCost.run(program,null,128,()->{});
        assertEquals(original,program.toJson().toString());
        var cost=ExecOptimizationCost.measure(ExecO3Test.main(program));
        assertFalse(ExecOptimizationCost.accepts(cost,new ExecOptimizationCost.Cost(cost.nodes(),257,
                cost.height(),cost.vmOperations(),cost.nativeOperations(),cost.resumeCells()),128));
    }
    @Test void smallerBodiesRetainTheirOptimization() throws Exception {
        var program=program("int main(){int x=1;return x+2;}");
        var optimized=program("int main(){return 3;}");
        var expected=ExecO3Test.main(optimized);
        ExecOptimizationCost.run(program,null,128,()-> {
            var main=ExecO3Test.main(program);main.mmp.clear();main.mmp.putAll(expected.mmp);
        });
        assertEquals(expected.toJson(),ExecO3Test.main(program).toJson());
    }
}
