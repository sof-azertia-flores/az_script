package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Move readonly scalar initializers only when they are safe even on the
 * zero-iteration path. Reuse their existing slots instead of growing frames. */
final class ExecRangeLoopMotion {
    private final ExecScalarAnalysis analysis;
    private final Map<Integer,Integer> allWrites=new HashMap<>(), allReads=new HashMap<>();
    private final int parameters;
    private int hoisted;
    private ExecRangeLoopMotion(AcsObject function, Map<Integer,Integer> returns) {
        analysis=new ExecScalarAnalysis(function,returns);
        parameters=function.getAsInt("param-count");
        references(function.mmp.get("script"),allWrites,allReads);
    }
    static void optimize(AcsObject function, Map<Integer,Integer> returns, Map<AcsElement,int[]> positions,
                         int abdLimit) {
        AcsElement original=function.mmp.get("script");
        function.mmp.put("script",ExecO2.copy(original,positions));
        var pass=new ExecRangeLoopMotion(function,returns);
        pass.blocks(function.mmp.get("script"));
        if(4L+ExecShape.height(function.mmp.get("script"))>abdLimit)function.mmp.put("script",original);
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
                    promote(statement,stable,output);
                }
            }
            blocks(statement);output.add(statement);
        }
        block.acsa.clear();block.acsa.addAll(output);
    }
    private void promote(AcsElement loop,Map<Integer,Value> stable,List<AcsElement> prefix) {
        if(!(field(loop,"val") instanceof AcsArray body))return;
        Map<Integer,Integer> loopReads=new HashMap<>();
        references(loop,new HashMap<>(),loopReads);
        List<AcsElement> remaining=new ArrayList<>();
        for(var item:body.acsa) {
            int slot=written(item);
            var site=analysis.sites.get(item);
            if(hoisted<64&&opcode(item)==DEFINE&&slot>=parameters
                    &&allWrites.getOrDefault(slot,0)==1
                    &&Objects.equals(allReads.getOrDefault(slot,0),loopReads.getOrDefault(slot,0))
                    &&site!=null&&site.in!=null&&dominatesUses(site,slot)) {
                var definition=(AcsObject)item;
                var value=field(item,"val");
                Value result=analysis.evaluate(value,stable);
                if(definition.mmp.containsKey("declared-type")&&result.total()&&result.scalar()
                        &&result.type()==number(item,"declared-type")
                        &&stable.keySet().containsAll(reads(value))) {
                    // A single write and no uses outside this loop also guard
                    // against slot reuse across disjoint lexical scopes.
                    prefix.add(item);stable.put(slot,result.evaluated());hoisted++;
                    continue;
                }
            }
            remaining.add(item);
        }
        body.acsa.clear();body.acsa.addAll(remaining);
    }
    private boolean dominatesUses(Block definition,int slot) {
        for(var block:analysis.blocks) {
            if(block.source==null||block.in==null)continue;
            var value=block.condition?field(block.source,"v"):block.source;
            if(reads(value).contains(slot)&&!block.dominators.get(definition.id))return false;
        }
        return true;
    }
    private static void references(AcsElement value,Map<Integer,Integer> writes,Map<Integer,Integer> reads) {
        int slot=written(value);
        if(slot>=0)writes.merge(slot,1,Integer::sum);
        if(opcode(value)==VARIABLE)reads.merge(number(value,"v"),1,Integer::sum);
        for(var child:ExecShape.children(value))references(child.value(),writes,reads);
    }
}
