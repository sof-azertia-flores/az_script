package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Loop speculation only when every operand and failure condition is proven
 * safe at the loop header, including the zero-iteration path. */
final class ExecRangeLoopMotion {
    private final AcsObject function;
    private final ExecScalarAnalysis analysis;
    private final Map<AcsElement,int[]> positions;
    private final int maxNodes, maxSlot;
    private int nodes, nextSlot, hoisted;
    private ExecRangeLoopMotion(AcsObject function, Map<Integer,Integer> returns,
                               Map<AcsElement,int[]> positions, int originalNodes, int originalSlots) {
        this.function=function;this.positions=positions;
        analysis=new ExecScalarAnalysis(function,returns);
        nodes=count(function.mmp.get("script"));
        maxNodes=originalNodes+Math.max(64,originalNodes/4);
        nextSlot=function.getAsInt("param-count")+function.getAsInt("local-count");
        maxSlot=Math.min(256,originalSlots+128);
    }
    static void optimize(AcsObject function, Map<Integer,Integer> returns, Map<AcsElement,int[]> positions,
                         int originalNodes, int originalSlots, int abdLimit) {
        AcsElement original=function.mmp.get("script");
        function.mmp.put("script",ExecO2.copy(original,positions));
        var pass=new ExecRangeLoopMotion(function,returns,positions,originalNodes,originalSlots);
        pass.blocks(function.mmp.get("script"));
        if(4L+ExecShape.height(function.mmp.get("script"))<=abdLimit)
            function.put("local-count",pass.nextSlot-function.getAsInt("param-count"));
        else function.mmp.put("script",original);
    }
    private void blocks(AcsElement source) {
        if(opcode(source)!=BLOCK) {
            if(opcode(source)==IF||opcode(source)==WHILE) {
                blocks(field(source,"val"));
                if(field(source,"else")!=null)blocks(field(source,"else"));
            }
            return;
        }
        var block=(AcsArray)source;
        List<AcsElement> output=new ArrayList<>();
        for(var statement:block.acsa) {
            if(opcode(statement)==WHILE) {
                var site=analysis.sites.get(statement);
                if(site!=null&&site.in!=null) {
                    Set<Integer> modified=new HashSet<>();writes(statement,modified);
                    Map<Integer,Value> stable=new HashMap<>(site.in);
                    modified.forEach(stable::remove);
                    motion(statement,stable,new HashMap<>(),output);
                }
            }
            blocks(statement);output.add(statement);
        }
        block.acsa.clear();block.acsa.addAll(output);
    }
    private void motion(AcsElement source,Map<Integer,Value> stable,Map<String,Integer> cached,
                        List<AcsElement> prefix) {
        for(var child:ExecShape.children(source)) {
            if(child.role()==ExecShape.Role.STORAGE_TARGET)continue;
            AcsElement value=child.value();
            int op=opcode(value);
            if(op!=CONSTANT&&op!=VARIABLE&&ExecO2.reusable(value)
                    &&stable.keySet().containsAll(reads(value))) {
                Value result=analysis.evaluate(value,stable);
                if(result.total()&&result.scalar()) {
                    String key=value.toJson().toString();
                    Integer slot=cached.get(key);
                    if(slot!=null) {
                        nodes+=1-count(value);
                        ExecOptimizer.replace(source,child,ExecO2.variable(slot,value,positions));
                        continue;
                    }
                    if(hoisted<64&&nextSlot<maxSlot&&nodes+2<=maxNodes) {
                        slot=nextSlot++;hoisted++;nodes+=2;cached.put(key,slot);
                        prefix.add(ExecO2.define(slot,result.type(),value,positions));
                        ExecOptimizer.replace(source,child,ExecO2.variable(slot,value,positions));
                        continue;
                    }
                }
            }
            motion(value,stable,cached,prefix);
        }
    }
}
