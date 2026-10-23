package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** In-place scalar calls: snapshots and callee locals live in the caller root,
 * while all assignments stay at the original expression evaluation point. */
final class ExecSequenceInliner {
    private record Candidate(AcsObject function,AcsArray body,Map<Integer,Integer> types,Set<Integer> written) {}
    private final AcsObject caller;
    private final Map<Integer,Candidate> candidates;
    private final Map<Integer,Integer> returns,types;
    private final List<AcsElement> declarations=new ArrayList<>();
    private final Map<AcsElement,int[]> positions;
    private final AcsArray inlined=new AcsArray();
    private final int maxNodes,abdLimit;
    private final boolean isolatedCaller;
    private int nextSlot,nodes;
    private ExecSequenceInliner(AcsObject caller,Map<Integer,Candidate> candidates,Map<Integer,Integer> returns,Map<AcsElement,int[]> positions,int abdLimit,int originalNodes,boolean isolatedCaller) {
        this.positions=positions;this.isolatedCaller=isolatedCaller;this.caller=caller;this.candidates=candidates;this.returns=returns;this.abdLimit=abdLimit;
        types=slotTypes(caller);nextSlot=caller.getAsInt("param-count")+caller.getAsInt("local-count");
        nodes=count(caller.mmp.get("script"));maxNodes=Math.min(MAX_NODES,originalNodes+Math.max(128,originalNodes/2));
    }
    static void optimize(AcsArray functions,Map<Integer,Integer> returns,Map<AcsElement,int[]> positions,int abdLimit) {
        Set<Integer> safe=safeFunctions(functions);Map<Integer,Candidate> candidates=new HashMap<>();
        // Freeze the original bodies once. Recursive rewriting has a literal four-frame
        // expansion bound, independent of declaration order and sibling expansions.
        for(var item:functions.acsa) {
            var function=(AcsObject)item;int id=function.getAsInt("id");
            if(!safe.contains(id)||!scalar(function.getAsInt("return-type"))||function.getAsInt("param-count")>16)continue;
            var debug=function.getAsAcsObject("debug");
            if(debug.getAsInt("line")<=0||debug.getAsInt("column")<=0)continue;
            if(!(function.mmp.get("script") instanceof AcsArray body))continue;
            if(body.acsa.isEmpty()||count(body)>128||opcode(body.acsa.get(body.acsa.size()-1))!=RETURN)continue;
            boolean eligible=true;
            for(int i=0;i<body.acsa.size()-1;i++) {
                int op=opcode(body.acsa.get(i));
                if(op!=DEFINE&&op!=SET&&op!=MOVE&&op!=CALL)eligible=false;
            }
            if(!eligible||slotTypes(function).values().stream().anyMatch(type->!scalar(type)))continue;
            var frozen=(AcsObject)ExecO2.copy(function,positions);
            Set<Integer> written=new HashSet<>();writes(frozen.getAsAcsArray("script"),written);
            candidates.put(id,new Candidate(frozen,frozen.getAsAcsArray("script"),slotTypes(frozen),Set.copyOf(written)));
        }
        for(var item:functions.acsa) {
            var function=(AcsObject)item;
            if(!(function.mmp.get("script") instanceof AcsArray)||function.getAsInt("entry-kind")==1)continue;
            var pass=new ExecSequenceInliner(function,candidates,returns,positions,abdLimit,count(function.mmp.get("script")),safe.contains(function.getAsInt("id")));
            AcsElement original=function.mmp.get("script");AcsArray body=(AcsArray)pass.rewrite(ExecO2.copy(original,positions),false,0);
            if(!pass.declarations.isEmpty()||!pass.inlined.acsa.isEmpty())
                pass.nextSlot=ExecSequenceCleanup.optimize(body,pass.declarations,positions,
                        function.getAsInt("param-count")+function.getAsInt("local-count"));
            body.acsa.addAll(0,pass.declarations);
            if(count(body)<=MAX_NODES&&pass.nextSlot<=256&&4L+ExecShape.height(body)<=abdLimit) {
                function.put("script",body);function.put("local-count",pass.nextSlot-function.getAsInt("param-count"));
                if(!pass.inlined.acsa.isEmpty())function.getAsAcsObject("debug").put("inlined",pass.inlined);
            }
        }
    }
    private AcsElement rewrite(AcsElement value,boolean cleanup,int depth) {
        int op=opcode(value);
        if(op==CLEANUP)cleanup=true;
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            ExecOptimizer.replace(value,child,rewrite(child.value(),cleanup,depth));
        if(op!=CALL||cleanup||depth>=4)return value;
        Candidate candidate=candidates.get(number(value,"id"));
        if(candidate==null||number(value,"id")==caller.getAsInt("id"))return value;
        // Calls publish failures before cleanup, and successful calls clear the
        // published trace. Only closed callers cannot observe either boundary.
        if(!isolatedCaller)return value;
        var call=(AcsObject)value;
        int[] callPosition=positions==null?null:positions.get(call);
        if(callPosition==null)return value; // Also applies without -g: O3 always retains compiler-side positions.
        if(call.mmp.containsKey("contexts")&&!call.getAsAcsArray("contexts").acsa.isEmpty())return value;
        var arguments=call.getAsAcsArray("param").acsa;var parameters=candidate.function.getAsAcsArray("param-types").acsa;
        if(arguments.size()!=parameters.size())return value;
        for(int i=0;i<arguments.size();i++)if(type(arguments.get(i))!=((AcsIntegerElement)parameters.get(i)).s)return value;
        AcsElement returned=field(candidate.body.acsa.get(candidate.body.acsa.size()-1),"r");
        // A same-type scalar identity has no callee work that can fail. Keep the
        // argument itself, including its caller-side location and single evaluation.
        if(arguments.size()==1&&candidate.body.acsa.size()==1&&opcode(returned)==VARIABLE
                &&number(returned,"v")==0&&candidate.function.getAsInt("return-type")==((AcsIntegerElement)parameters.get(0)).s) {
            nodes+=count(arguments.get(0))-count(value);
            return arguments.get(0);
        }
        var substituted=new HashMap<Integer,AcsElement>();
        Set<Integer> laterWrites=new HashSet<>();
        for(int i=arguments.size()-1;i>=0;i--) {
            AcsElement argument=arguments.get(i);
            // Closed scalar calls cannot reach caller cells. Only writes in
            // later argument expressions can change a borrowed local value.
            if(!candidate.written.contains(i)&&leaf(argument)
                    &&(opcode(argument)!=VARIABLE||!laterWrites.contains(number(argument,"v"))))
                substituted.put(i,argument);
            writes(argument,laterWrites);
        }
        if(nextSlot+candidate.types.size()-substituted.size()>256)return value;
        var slots=new LinkedHashMap<Integer,Integer>();
        for(int old:new TreeSet<>(candidate.types.keySet()))if(!substituted.containsKey(old))slots.put(old,nextSlot+slots.size());
        var statements=new AcsArray();
        for(int i=0;i<arguments.size();i++)if(slots.containsKey(i))statements.acsa.add(set(slots.get(i),arguments.get(i)));
        int argumentStatements=statements.acsa.size();
        for(int i=0;i<candidate.body.acsa.size()-1;i++) {
            AcsElement statement=rename(ExecO2.copy(candidate.body.acsa.get(i),positions),slots,substituted);
            if(opcode(statement)==DEFINE) {
                var definition=(AcsObject)statement;int type=definition.getAsInt("declared-type");
                statement=ExecO2.locate(set(definition.getAsInt("v"),definition.mmp.getOrDefault("val",zero(type))),definition,positions);
            }
            statements.acsa.add(statement);
        }
        AcsElement result=rename(ExecO2.copy(returned,positions),slots,substituted);
        var sequence=new AcsObject();sequence.put("t",0);sequence.put("c",SEQUENCE);sequence.put("statements",statements);sequence.put("value",result);
        int delta=count(sequence)-count(value)+slots.size()*2;
        if(nodes+delta>maxNodes||4L+ExecShape.height(sequence)>abdLimit)return value;
        for(var slot:slots.entrySet()) {
            int type=candidate.types.get(slot.getKey());types.put(slot.getValue(),type);
            declarations.add(ExecO2.define(slot.getValue(),type,zero(type),null));
        }
        nodes+=delta;nextSlot+=slots.size();
        int inlineIndex=inlined.acsa.size()+1,parent=callPosition.length>3?callPosition[3]:0;
        var header=candidate.function.getAsAcsObject("debug");var inline=new AcsObject();
        for(String name:List.of("name","file","line","column"))inline.mmp.put(name,header.mmp.get(name));
        inline.put("call-site",0);inlined.acsa.add(inline);
        positions.put(sequence,new int[]{callPosition[0],callPosition[1],callPosition[2],parent,inlineIndex});
        // Argument snapshots retain the caller's inline chain. Only callee work
        // receives the new frame; an argument error must never invent that frame.
        for(int i=argumentStatements;i<statements.acsa.size();i++) {
            AcsElement statement=statements.acsa.get(i);
            annotate(statement,inlineIndex,new int[]{header.getAsInt("file"),header.getAsInt("line"),header.getAsInt("column")});
            statements.acsa.set(i,rewrite(statement,false,depth+1));
        }
        annotate(result,inlineIndex,positions.getOrDefault(candidate.body.acsa.get(candidate.body.acsa.size()-1),
                new int[]{header.getAsInt("file"),header.getAsInt("line"),header.getAsInt("column")}));
        sequence.put("value",rewrite(result,false,depth+1));return sequence;
    }
    private void annotate(AcsElement value,int inlineIndex,int[] inherited) {
        int[] at=positions.getOrDefault(value,inherited);
        if(at!=null&&at[1]>0&&at[2]>0)positions.put(value,new int[]{at[0],at[1],at[2],inlineIndex});
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            annotate(child.value(),inlineIndex,at);
    }
    private int type(AcsElement value) {
        if(value instanceof AcsIntegerElement)return INT_VALUE;
        if(value instanceof AcsBooleanElement)return BOOLEAN_VALUE;
        int op=opcode(value);
        if(op==VARIABLE)return isolatedCaller?types.getOrDefault(number(value,"v"),ANY_VALUE):ANY_VALUE;
        if(op==CALL)return returns.getOrDefault(number(value,"id"),ANY_VALUE);
        if(op==SEQUENCE)return type(field(value,"value"));
        if(op==CONVERT)return number(value,"type");
        if(op>=GREATER&&op<=NOT)return BOOLEAN_VALUE;
        if(op>=BIT_AND&&op<=SHIFT_UNSIGNED||op>=ADD_I32_UNCHECKED&&op<=INCREMENT_I32_UNCHECKED)return INT_VALUE;
        if(op==NEGATE)return type(field(value,"v"));
        if(op==SELECT) {int a=type(field(value,"then")),b=type(field(value,"else"));return a==b?a:ANY_VALUE;}
        if(op>=ADD&&op<=MODULO) {int a=type(field(value,"v1")),b=type(field(value,"v2"));return a==b?a:ANY_VALUE;}
        return ANY_VALUE;
    }
    private static AcsElement zero(int type) {return type==BOOLEAN_VALUE?new AcsBooleanElement(false):new AcsIntegerElement(0);}
    private static boolean leaf(AcsElement value) {
        return value instanceof AcsIntegerElement||value instanceof AcsBooleanElement||opcode(value)==VARIABLE;
    }
    private static void writes(AcsElement value,Set<Integer> written) {
        int op=opcode(value);
        if(op==DEFINE||op==SET)written.add(number(value,"v"));
        else if(op==MOVE&&opcode(field(value,"v1"))==VARIABLE)written.add(number(field(value,"v1"),"v"));
        for(var child:ExecShape.children(value))writes(child.value(),written);
    }
    private static AcsObject set(int slot,AcsElement value) {
        var result=new AcsObject();result.put("t",0);result.put("c",SET);result.put("v",slot);result.put("val",value);return result;
    }
    private static Map<Integer,Integer> slotTypes(AcsObject function) {
        var types=new HashMap<Integer,Integer>();var parameters=function.getAsAcsArray("param-types").acsa;
        for(int i=0;i<parameters.size();i++)types.put(i,((AcsIntegerElement)parameters.get(i)).s);
        collectTypes(function.mmp.get("script"),types);return types;
    }
    private static void collectTypes(AcsElement value,Map<Integer,Integer> types) {
        if(opcode(value)==DEFINE)types.put(number(value,"v"),((AcsObject)value).mmp.containsKey("declared-type")?number(value,"declared-type"):ANY_VALUE);
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)collectTypes(child.value(),types);
    }
    private AcsElement rename(AcsElement value,Map<Integer,Integer> slots,Map<Integer,AcsElement> substituted) {
        int op=opcode(value);
        if(op==VARIABLE&&substituted.containsKey(number(value,"v")))return ExecO2.copy(substituted.get(number(value,"v")),positions);
        if(op==VARIABLE||op==DEFINE||op==SET) {
            int old=number(value,"v");if(old<0||!slots.containsKey(old))throw new IllegalStateException("Non-local scalar inline slot");
            ((AcsObject)value).put("v",slots.get(old).intValue());
        }
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            ExecOptimizer.replace(value,child,rename(child.value(),slots,substituted));
        return value;
    }
}
