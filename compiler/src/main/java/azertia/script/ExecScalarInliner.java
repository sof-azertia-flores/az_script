package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** O2 inlining at eager statement roots, with explicit argument snapshots.
 * It deliberately introduces no expression scope or runtime instruction.
 */
final class ExecScalarInliner {
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,Integer> returns;
    private final Map<Integer,Candidate> candidates;
    private final ExecScalarAnalysis analysis;
    private final AcsObject caller;
    private final int maxNodes, maxSlot, abdLimit;
    private int nodes, nextSlot;
    private record Candidate(AcsObject function, List<AcsElement> body) {}

    private ExecScalarInliner(AcsObject caller, Map<Integer,Candidate> candidates,
            Map<Integer,Integer> returns, Map<AcsElement,int[]> positions,
            int originalNodes, int originalSlots, int abdLimit) {
        this.caller=caller; this.candidates=candidates; this.returns=returns;
        this.positions=positions; this.abdLimit=abdLimit;
        analysis=new ExecScalarAnalysis(caller,returns);
        nodes=count(caller.mmp.get("script"));
        maxNodes=originalNodes+Math.max(64,originalNodes/4);
        nextSlot=caller.getAsInt("param-count")+caller.getAsInt("local-count");
        maxSlot=Math.min(256,originalSlots+128);
    }
    static void optimize(AcsArray functions, Map<AcsElement,int[]> positions,
                         Map<Integer,Integer> originalNodes, Map<Integer,Integer> originalSlots, int abdLimit) {
        Map<Integer,Integer> returns=new HashMap<>();
        for(var item:functions.acsa) {
            var function=(AcsObject)item;returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        // Freeze each round's candidates: source declaration order must not
        // turn a depth-two expansion limit into arbitrary transitive inlining.
        for(int round=0;round<2;round++) {
            Set<Integer> safe=safeFunctions(functions);
            Map<Integer,Candidate> candidates=new HashMap<>();
            for(var item:functions.acsa) {
                var function=(AcsObject)item;
                if(!safe.contains(function.getAsInt("id")))continue;
                Candidate candidate=candidate(function,returns,positions);
                if(candidate!=null)candidates.put(function.getAsInt("id"),candidate);
            }
            for(var item:functions.acsa) {
                var function=(AcsObject)item;int id=function.getAsInt("id");
                if(!safe.contains(id))continue;
                var pass=new ExecScalarInliner(function,candidates,returns,positions,
                        originalNodes.get(id),originalSlots.get(id),abdLimit);
                AcsElement original=function.mmp.get("script");
                int locals=function.getAsInt("local-count");
                // Rewrite a copy, retaining a complete rollback at the ABD
                // nesting boundary. Analysis sites refer to the old tree.
                AcsElement body=pass.block(original);
                if(4L+ExecShape.height(body)<=abdLimit) {
                    function.mmp.put("script",body);
                    function.put("local-count",pass.nextSlot-function.getAsInt("param-count"));
                } else {
                    function.mmp.put("script",original);function.put("local-count",locals);
                }
            }
        }
    }
    private static Candidate candidate(AcsObject function, Map<Integer,Integer> returns,
                                       Map<AcsElement,int[]> positions) {
        if(!scalar(function.getAsInt("return-type")) || function.getAsInt("param-count")>8)return null;
        AcsElement script=function.mmp.get("script");
        if(count(script)>64 || !(script instanceof AcsArray block) || block.acsa.isEmpty())return null;
        for(int i=0;i<block.acsa.size();i++) {
            AcsElement item=block.acsa.get(i);int op=opcode(item);
            if(i==block.acsa.size()-1) {if(op!=RETURN)return null;}
            else if(op!=DEFINE&&op!=SET&&op!=MOVE)return null;
            for(var child:ExecShape.children(item))if(!ExecO2.reusable(child.value()))return null;
        }
        var analysis=new ExecScalarAnalysis(function,returns);
        var returned=block.acsa.get(block.acsa.size()-1);
        var site=analysis.sites.get(returned);
        if(site==null||site.in==null
                ||analysis.evaluate(field(returned,"r"),site.in).type()!=function.getAsInt("return-type"))return null;
        var frozen=(AcsObject)ExecO2.copy(function,positions);
        return new Candidate(frozen,((AcsArray)frozen.mmp.get("script")).acsa);
    }
    private AcsElement block(AcsElement original) {
        if(opcode(original)!=BLOCK) {
            var result=ExecO2.copy(original,positions);
            if(opcode(original)==IF||opcode(original)==WHILE) {
                var object=(AcsObject)result;
                object.mmp.put("val",block(field(original,"val")));
                if(field(original,"else")!=null)object.mmp.put("else",block(field(original,"else")));
            }
            return result;
        }
        var result=new AcsArray();
        for(var statement:((AcsArray)original).acsa) {
            var expansion=expand(statement);
            if(expansion!=null)result.acsa.addAll(expansion);
            else result.acsa.add(block(statement));
        }
        return ExecO2.locate(result,original,positions);
    }
    private List<AcsElement> expand(AcsElement statement) {
        int op=opcode(statement);
        String field=op==RETURN?"r":op==DEFINE||op==SET?"val":op==MOVE?"v2":null;
        if(field==null)return null;
        AcsElement value=field(statement,field);
        if(value==null||opcode(value)!=CALL)return null;
        Candidate candidate=candidates.get(number(value,"id"));
        if(candidate==null)return null;
        var site=analysis.sites.get(statement);
        if(site==null||site.in==null)return null;
        if(op==SET||op==MOVE)if(!site.in.containsKey(written(statement)))return null;
        var arguments=((AcsObject)value).getAsAcsArray("param").acsa;
        var parameters=candidate.function.getAsAcsArray("param-types").acsa;
        if(arguments.size()!=parameters.size())return null;
        for(int i=0;i<arguments.size();i++)
            if(analysis.evaluate(arguments.get(i),site.in).type()!=((AcsIntegerElement)parameters.get(i)).s)return null;
        int slots=parameters.size()+candidate.function.getAsInt("local-count");
        if(nextSlot+slots>maxSlot)return null;
        int base=nextSlot;
        List<AcsElement> prefix=new ArrayList<>();
        for(int i=0;i<arguments.size();i++)
            prefix.add(ExecO2.define(base+i,((AcsIntegerElement)parameters.get(i)).s,
                    ExecO2.copy(arguments.get(i),positions),positions));
        for(int i=0;i<candidate.body.size()-1;i++)prefix.add(renamed(candidate.body.get(i),base));
        AcsElement returned=renamed(field(candidate.body.get(candidate.body.size()-1),"r"),base);
        var replacement=(AcsObject)ExecO2.copy(statement,positions);
        replacement.mmp.put(field,returned);prefix.add(replacement);
        int delta=prefix.stream().mapToInt(ExecScalarAnalysis::count).sum()-count(statement);
        if(nodes+delta>maxNodes)return null;
        nodes+=delta;nextSlot+=slots;return prefix;
    }
    private AcsElement renamed(AcsElement original,int base) {
        AcsElement result=ExecO2.copy(original,positions);
        rename(result,base);return result;
    }
    private static void rename(AcsElement value,int base) {
        int op=opcode(value);
        if(op==VARIABLE||op==DEFINE||op==SET) {
            int slot=number(value,"v");
            if(slot<0)throw new IllegalStateException("Global in a scalar inline candidate");
            ((AcsObject)value).put("v",base+slot);
        }
        for(var child:ExecShape.children(value))rename(child.value(),base);
    }
}
