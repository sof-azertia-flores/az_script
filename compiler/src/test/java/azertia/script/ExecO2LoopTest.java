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
    @Test void rangeProofMovesWholeInitializersWithoutAddingSlots() throws Exception {
        String source="int f(int n){int s=0;int i=0;while(i<10){int v=((n&7)+1)*3/((n&3)+1);s+=v;i+=1;}return s;}";
        var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
        assertEquals(before.getAsInt("local-count"),after.getAsInt("local-count"));
        var loop=nodes(after.mmp.get("script"),WHILE).get(0);
        assertEquals(0,ExecO2OptimizerTest.count(loop,MULTIPLY));
        assertEquals(1,ExecO2OptimizerTest.count(after.mmp.get("script"),MULTIPLY));
    }
    @Test void branchRangesProveSafetyButFullIntInputsDoNot() throws Exception {
        String guarded="int f(int n){if(n>=0&&n<=100){int i=0;int s=0;while(i<10){int v=n*n/3;s+=v;i+=1;}return s;}return 0;}";
        var after=function(guarded,Set.of(RANGE_LOOP_MOTION));
        assertEquals(0,ExecO2OptimizerTest.count(nodes(after.mmp.get("script"),WHILE).get(0),MULTIPLY));
        String unguarded="int f(int n){int s=0;int i=0;while(i<10){int v=n*n/3;s+=v;i+=1;}return s;}";
        var untouched=function(unguarded,Set.of(RANGE_LOOP_MOTION));
        assertEquals(1,ExecO2OptimizerTest.count(nodes(untouched.mmp.get("script"),WHILE).get(0),MULTIPLY));
    }
    @Test void cheapArithmeticDoesNotStackAnotherTemporaryOnO1LoopMotion() throws Exception {
        String source="int f(int n){int s=0;int i=0;while(i<10){s+=((n&7)+1)*3;i+=1;}return s;}";
        var passes=EnumSet.allOf(Compiler.OptimizationPass.class);
        passes.remove(RANGE_LOOP_MOTION);
        var before=function(source,passes);
        passes.add(RANGE_LOOP_MOTION);
        var after=function(source,passes);
        assertEquals(before.getAsInt("local-count"),after.getAsInt("local-count"));
        assertEquals(1,ExecO2OptimizerTest.count(nodes(after.mmp.get("script"),WHILE).get(0),MULTIPLY));
    }
    @Test void zeroTripAndVariantOperationsStayInside() throws Exception {
        for(String source:List.of(
                "int f(int n){int i=0;int s=0;while(i<0){int v=1/n;s+=v;i+=1;}return s;}",
                "int f(int n){int i=0;int s=0;while(i<0){int v=n+1;s+=v;i+=1;}return s;}",
                "int f(int n){int i=0;int s=0;while(i<10){int v=(n&7)+1;s+=v;n+=1;i+=1;}return s;}")) {
            var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
            assertEquals(before.toJson().toString(),after.toJson().toString());
        }
    }
    @Test void aLocalModifiedAfterInitializationStillResetsOnEveryIteration() throws Exception {
        String source="int f(int n){int i=0;int s=0;while(i<10){int v=(n&7)+1;v+=i;s+=v;i+=1;}return s;}";
        var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
        assertEquals(before.toJson().toString(),after.toJson().toString());
    }
    @Test void promotionLimitPreservesRemainingInitializersAndStorage() throws Exception {
        var body=new StringBuilder();
        for(int i=0;i<70;i++)body.append("int v").append(i).append("=(n&7)+1;s+=v").append(i).append(";");
        String source="int f(int n){int s=0;int i=0;while(i<2){"+body+"i+=1;}return s;}";
        var before=function(source,Set.of());var after=function(source,Set.of(RANGE_LOOP_MOTION));
        assertEquals(before.getAsInt("local-count"),after.getAsInt("local-count"));
        assertEquals(6,ExecO2OptimizerTest.count(nodes(after.mmp.get("script"),WHILE).get(0),DEFINE));
        assertEquals(ExecO2OptimizerTest.count(before.mmp.get("script"),DEFINE),
                ExecO2OptimizerTest.count(after.mmp.get("script"),DEFINE));
    }
    @Test void constantConditionalResultDoesNotHideAConditionTrap() throws Exception {
        var after=function("int f(int n){return (n+1>2147483647)?1:2;}",Set.of(PROPAGATION,CONTROL_FLOW));
        assertEquals(1,ExecO2OptimizerTest.count(after.mmp.get("script"),ADD));
    }
}
