package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecO2LoopTest {
    private AcsObject function(String source,Set<Compiler.OptimizationPass> passes) throws Exception {
        return ExecO2AnalysisTest.first(ExecO2OptimizerTest.compile(source,passes));
    }
    private static List<AcsElement> nodes(AcsElement value,int opcode) {
        List<AcsElement> result=new ArrayList<>();
        if(ExecScalarAnalysis.opcode(value)==opcode)result.add(value);
        for(var child:ExecShape.children(value))result.addAll(nodes(child.value(),opcode));
        return result;
    }
    @Test void rangeProofHoistsWholeArithmeticInsteadOfOnlyBitOperations() throws Exception {
        String source="int f(int n){int s=0;int i=0;while(i<10){s+=((n&7)+1)*3;i+=1;}return s;}";
        var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
        assertEquals(before.getAsInt("local-count")+1,after.getAsInt("local-count"));
        var loop=nodes(after.mmp.get("script"),WHILE).get(0);
        assertEquals(0,ExecO2OptimizerTest.count(loop,MULTIPLY));
        assertEquals(1,ExecO2OptimizerTest.count(after.mmp.get("script"),MULTIPLY));
    }
    @Test void branchRangesProveSafetyButFullIntInputsDoNot() throws Exception {
        String guarded="int f(int n){if(n>=0&&n<=100){int i=0;int s=0;while(i<10){s+=n*n;i+=1;}return s;}return 0;}";
        var after=function(guarded,Set.of(RANGE_LOOP_MOTION));
        assertEquals(0,ExecO2OptimizerTest.count(nodes(after.mmp.get("script"),WHILE).get(0),MULTIPLY));
        String unguarded="int f(int n){int s=0;int i=0;while(i<10){s+=n*n;i+=1;}return s;}";
        var untouched=function(unguarded,Set.of(RANGE_LOOP_MOTION));
        assertEquals(1,ExecO2OptimizerTest.count(nodes(untouched.mmp.get("script"),WHILE).get(0),MULTIPLY));
    }
    @Test void zeroTripAndVariantOperationsStayInside() throws Exception {
        for(String source:List.of(
                "int f(int n){int i=0;int s=0;while(i<0){s+=1/n;i+=1;}return s;}",
                "int f(int n){int i=0;int s=0;while(i<0){s+=n+1;i+=1;}return s;}",
                "int f(int n){int i=0;int s=0;while(i<10){s+=(n&7)+1;n+=1;i+=1;}return s;}")) {
            var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
            assertEquals(before.getAsInt("local-count"),after.getAsInt("local-count"));
        }
    }
    @Test void constantConditionalResultDoesNotHideAConditionTrap() throws Exception {
        var after=function("int f(int n){return (n+1>2147483647)?1:2;}",Set.of(PROPAGATION,CONTROL_FLOW));
        assertEquals(1,ExecO2OptimizerTest.count(after.mmp.get("script"),ADD));
    }
}
