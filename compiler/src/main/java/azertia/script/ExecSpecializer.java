package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Deterministic private versions, bounded independently of source declaration order. */
final class ExecSpecializer {
    /** Source-only mapping: generic slot/return types cannot be reconstructed from ABI any. */
    record GenericSignature(int returnContext,Map<Integer,Integer> slots) {}
    private final AcsArray functions;
    private final Set<Compiler.OptimizationPass> passes;
    private final int abdLimit;
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,GenericSignature> signatures;
    private final Map<Integer,AcsObject> originals=new LinkedHashMap<>(),generated=new HashMap<>();
    private final Map<String,Integer> versions=new LinkedHashMap<>();
    private final Map<Integer,Integer> counts=new HashMap<>();
    private final Set<Integer> used=new HashSet<>(),safe,closed;
    private final List<AcsElement> added=new ArrayList<>();
    private ExecSpecializer(AcsArray functions,Set<Compiler.OptimizationPass> passes,Map<AcsElement,int[]> positions,
                            Map<Integer,GenericSignature> signatures,int abdLimit) {
        this.positions=positions;this.signatures=signatures;this.functions=functions;this.passes=passes;this.abdLimit=abdLimit;
        safe=safeFunctions(functions);
        for(var item:functions.acsa) {var f=(AcsObject)item;originals.put(f.getAsInt("id"),f);used.add(f.getAsInt("id"));}
        closed=closedFunctions();
    }
    static void optimize(AcsObject program,Set<Compiler.OptimizationPass> passes,Map<AcsElement,int[]> positions,
                         Map<Integer,GenericSignature> signatures,int abdLimit) {
        AcsArray functions=program.getAsAcsArray("f");
        var pass=new ExecSpecializer(functions,passes,positions,signatures,abdLimit);
        for(var signature:program.getAsAcsArray("extern-signatures").acsa)pass.used.add(((AcsObject)signature).getAsInt("id"));
        for(var item:List.copyOf(functions.acsa)) {
            var caller=(AcsObject)item;
            caller.put("script",pass.visit(caller.mmp.get("script"),pass.closed.contains(caller.getAsInt("id")),slotTypes(caller)));
        }
        functions.acsa.addAll(pass.added);
    }
    private AcsElement visit(AcsElement value,boolean scalarCaller,Map<Integer,Integer> callerTypes) {
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            ExecOptimizer.replace(value,child,visit(child.value(),scalarCaller,callerTypes));
        if(opcode(value)==CHECK_TYPE&&scalarContext(field(value,"context"))) {
            int expected=number(field(value,"context"),"abi");
            if(actualType(field(value,"v"),scalarCaller?callerTypes:Map.of())==expected)return uncheckedValue(value);
        }
        if(opcode(value)!=CALL)return value;
        specialize((AcsObject)value,scalarCaller,callerTypes);return value;
    }
    private void specialize(AcsObject call,boolean scalarCaller,Map<Integer,Integer> callerTypes) {
        AcsObject original=originals.get(call.getAsInt("id"));
        if(original==null||original.getAsInt("entry-kind")!=0||count(original.mmp.get("script"))>MAX_NODES)return;
        int id=original.getAsInt("id");var arguments=call.getAsAcsArray("param");
        AcsArray contexts=call.mmp.containsKey("contexts")?call.getAsAcsArray("contexts"):new AcsArray();
        boolean context=passes.contains(CONTEXT_SPECIALIZATION)&&original.getAsInt("hidden-count")>0
                &&contexts.acsa.size()==original.getAsInt("hidden-count")&&contexts.acsa.stream().allMatch(ExecSpecializer::fixed);
        var constants=new TreeMap<Integer,AcsElement>();
        if(passes.contains(CONSTANT_SPECIALIZATION)&&scalarCaller&&closed.contains(id))for(int i=0;i<arguments.acsa.size();i++) {
            var argument=arguments.acsa.get(i);
            if(argument instanceof AcsIntegerElement||argument instanceof AcsBooleanElement)constants.put(i,argument);
        }
        if(constants.isEmpty()&&!context)return;
        var clone=(AcsObject)ExecO2.copy(original,positions);
        boolean concrete=false;
        if(context) {
            bind(clone.mmp.get("script"),contexts);
            if(contexts.acsa.stream().allMatch(ExecSpecializer::scalarContext)&&closed.contains(id)&&signatures.containsKey(id)) {
                concrete=concretize(clone,signatures.get(id),contexts);
                if(concrete) {
                    var expected=clone.getAsAcsArray("param-types").acsa;
                    if(arguments.acsa.size()!=expected.size())return;
                    for(int i=0;i<expected.size();i++)
                        if(actualType(arguments.acsa.get(i),scalarCaller?callerTypes:Map.of())!=((AcsIntegerElement)expected.get(i)).s)return;
                }
            }
            if(!concrete) {
                clone=(AcsObject)ExecO2.copy(original,positions);bind(clone.mmp.get("script"),contexts);
                clone.put("script",simplifyContexts(clone.mmp.get("script"),Map.of(),false));
            }
        }
        if(!safe.contains(id)&&!concrete)constants.clear();
        String key=id+":"+constants.entrySet().stream().map(e->e.getKey()+"="+e.getValue().toJson()).toList()
                +":"+(context?contexts.toJson().toString():"")+":"+concrete;
        Integer existing=versions.get(key);
        if(existing!=null) {call.put("id",existing.intValue());if(concrete)call.put("contexts",new AcsArray());return;}
        if(added.size()>=64||counts.getOrDefault(id,0)>=4)return;
        AcsElement body=clone.mmp.get("script");
        if(!constants.isEmpty()) {
            if(!(body instanceof AcsArray block))return;
            List<AcsElement> prefix=new ArrayList<>();
            for(var item:constants.entrySet()) {
                var assign=new AcsObject();assign.put("t",0);assign.put("c",SET);assign.put("v",item.getKey().intValue());
                assign.put("val",ExecO2.copy(item.getValue(),positions));prefix.add(assign);
            }
            block.acsa.addAll(0,prefix);
        }
        if(!constants.isEmpty()||concrete) {
            var optimizationFunctions=new AcsArray();
            for(var item:functions.acsa)if(((AcsObject)item).getAsInt("id")!=id)optimizationFunctions.acsa.add(ExecO2.copy(item,positions));
            optimizationFunctions.acsa.add(clone);
            ExecO2.optimize(optimizationFunctions,Set.of(CONTROL_FLOW,COMMON_SUBEXPRESSIONS,DEAD_CODE,FLOW_PROPAGATION),positions,abdLimit);
            body=clone.mmp.get("script");
        }
        int before=count(original.mmp.get("script")),after=count(body);
        if((after*4>before*3&&!(concrete&&inlineable(clone)))||4L+ExecShape.height(body)>abdLimit)return;
        Integer privateId=allocate(id>>>16);if(privateId==null)return;
        clone.put("id",privateId.intValue());clone.put("entry-kind",2);
        added.add(clone);generated.put(privateId,clone);versions.put(key,privateId);counts.merge(id,1,Integer::sum);
        call.put("id",privateId.intValue());if(concrete)call.put("contexts",new AcsArray());
    }
    private boolean concretize(AcsObject clone,GenericSignature signature,AcsArray contexts) {
        var types=slotTypes(clone);
        for(var entry:signature.slots.entrySet())types.put(entry.getKey(),number(contexts.acsa.get(entry.getValue()),"abi"));
        var parameters=clone.getAsAcsArray("param-types");
        for(int i=0;i<parameters.acsa.size();i++) {
            int type=types.getOrDefault(i,ANY_VALUE);if(!scalar(type))return false;parameters.acsa.set(i,new AcsIntegerElement(type));
        }
        int returned=clone.getAsInt("return-type");
        if(signature.returnContext>=0)returned=number(contexts.acsa.get(signature.returnContext),"abi");
        if(!scalar(returned)&&returned!=VOID_VALUE)return false;
        specializeSlots(clone.mmp.get("script"),signature,types);
        clone.put("script",simplifyContexts(clone.mmp.get("script"),types,true));
        clone.put("return-type",returned);clone.put("hidden-count",0);
        return ExecScalarAnalysis.eligible(clone,safe);
    }
    private static void specializeSlots(AcsElement value,GenericSignature signature,Map<Integer,Integer> types) {
        if(opcode(value)==DEFINE&&signature.slots.containsKey(number(value,"v")))((AcsObject)value).put("declared-type",types.get(number(value,"v")).intValue());
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            specializeSlots(child.value(),signature,types);
    }
    private AcsElement simplifyContexts(AcsElement value,Map<Integer,Integer> types,boolean scalar) {
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            ExecOptimizer.replace(value,child,simplifyContexts(child.value(),types,scalar));
        int op=opcode(value);AcsElement result=value;
        if(op==CONTEXT_ABI) {
            var context=((AcsObject)value).getAsAcsObject("context");
            if(context.mmp.containsKey("abi"))result=new AcsIntegerElement(context.getAsInt("abi"));
        } else if(op==CONTEXT_DEFAULT&&scalarContext(field(value,"context"))) {
            result=number(field(value,"context"),"abi")==INT_VALUE?new AcsIntegerElement(0):new AcsBooleanElement(false);
        } else if(scalar&&(op==CHECK_TYPE||op==RETURN_TYPED)&&scalarContext(field(value,"context"))
                &&actualType(field(value,"v"),types)==number(field(value,"context"),"abi")) {
            if(op==CHECK_TYPE)return uncheckedValue(value);
            var returned=new AcsObject();returned.put("t",0);returned.put("c",RETURN);returned.put("r",field(value,"v"));result=returned;
        }
        result=ExecOptimizer.fold(result);
        if(op==IF&&field(value,"v") instanceof AcsBooleanElement condition)
            result=field(value,condition.on?"val":"else")==null?new AcsArray():field(value,condition.on?"val":"else");
        return ExecO2.locate(result,value,positions);
    }
    private AcsElement uncheckedValue(AcsElement checked) {
        AcsElement value=field(checked,"v");
        // Generic calls may carry their only source location on the type check.
        // Preserve a child's more precise location when it already has one.
        return positions!=null&&positions.containsKey(value)?value:ExecO2.locate(value,checked,positions);
    }
    private int actualType(AcsElement value,Map<Integer,Integer> types) {
        if(value instanceof AcsIntegerElement)return INT_VALUE;
        if(value instanceof AcsBooleanElement)return BOOLEAN_VALUE;
        int op=opcode(value);
        if(op==VARIABLE)return types.getOrDefault(number(value,"v"),ANY_VALUE);
        if(op==CALL) {
            var function=generated.getOrDefault(number(value,"id"),originals.get(number(value,"id")));
            return function==null?ANY_VALUE:function.getAsInt("return-type");
        }
        if(op==CHECK_TYPE&&scalarContext(field(value,"context")))return number(field(value,"context"),"abi");
        if(op==CONVERT)return number(value,"type");
        if(op>=GREATER&&op<=NOT)return BOOLEAN_VALUE;
        if(op>=BIT_AND&&op<=SHIFT_UNSIGNED)return INT_VALUE;
        if(op==NEGATE)return actualType(field(value,"v"),types);
        if(op==SELECT) {int a=actualType(field(value,"then"),types),b=actualType(field(value,"else"),types);return a==b?a:ANY_VALUE;}
        if(op>=ADD&&op<=MODULO) {int a=actualType(field(value,"v1"),types),b=actualType(field(value,"v2"),types);return a==b?a:ANY_VALUE;}
        return ANY_VALUE;
    }
    /** Generic source can be closed over int/bool without already being concretized.
     * Explicit any, host calls, heap identities, recursion and object contexts never qualify. */
    private Set<Integer> closedFunctions() {
        Map<Integer,Set<Integer>> pending=new LinkedHashMap<>();Set<Integer> result=new HashSet<>();
        for(var f:originals.values()) {
            int id=f.getAsInt("id");var signature=signatures.get(id);var types=slotTypes(f);
            if(f.getAsInt("entry-kind")!=0||types.size()>256||count(f.mmp.get("script"))>MAX_NODES)continue;
            if(types.entrySet().stream().anyMatch(e->!scalar(e.getValue())&&(signature==null||!signature.slots.containsKey(e.getKey()))))continue;
            int returned=f.getAsInt("return-type");
            if(!scalar(returned)&&returned!=VOID_VALUE&&(signature==null||signature.returnContext<0))continue;
            var dependencies=new HashSet<Integer>();if(closedShape(f.mmp.get("script"),dependencies))pending.put(id,dependencies);
        }
        boolean changed;
        do {
            changed=false;
            for(var item:pending.entrySet())if(!result.contains(item.getKey())&&result.containsAll(item.getValue()))changed|=result.add(item.getKey());
        } while(changed);
        return Set.copyOf(result);
    }
    private boolean closedShape(AcsElement value,Set<Integer> dependencies) {
        if(value==null)return true;
        int op=opcode(value);
        if(op==CONSTANT)return value instanceof AcsIntegerElement||value instanceof AcsBooleanElement;
        if(op==VARIABLE||op==DEFINE||op==SET) {if(number(value,"v")<0)return false;}
        else if(op==CALL) {
            if(!originals.containsKey(number(value,"id")))return false;dependencies.add(number(value,"id"));
            var call=(AcsObject)value;
            if(call.mmp.containsKey("contexts")&&!call.getAsAcsArray("contexts").acsa.stream().allMatch(ExecSpecializer::scalarOrReference))return false;
        } else if(op==MOVE) {
            if(opcode(field(value,"v1"))!=VARIABLE||number(field(value,"v1"),"v")<0)return false;
        } else if(op==CONTEXT_ABI||op==CONTEXT_DEFAULT||op==RETURN_TYPED||op==CHECK_TYPE) {
            if(!scalarOrReference(field(value,"context")))return false;
        } else if(!(op==BLOCK||op==RETURN||op>=ADD&&op<=CONTINUE||op>=BIT_AND&&op<=SELECT))return false;
        if(op==CONVERT&&!scalar(number(value,"type")))return false;
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                &&!closedShape(child.value(),dependencies))return false;
        return true;
    }
    private static boolean scalarContext(AcsElement value) {
        return value instanceof AcsObject context&&!context.mmp.containsKey("ref")&&context.getAsInt("kind")==0&&scalar(context.getAsInt("abi"));
    }
    private static boolean scalarOrReference(AcsElement value) {
        return value instanceof AcsObject context&&(context.mmp.containsKey("ref")||scalarContext(context));
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
    private static boolean inlineable(AcsObject function) {
        if(function.getAsInt("param-count")>16||!(function.mmp.get("script") instanceof AcsArray body)||body.acsa.isEmpty()||count(body)>128)return false;
        for(int i=0;i<body.acsa.size();i++) {
            int op=opcode(body.acsa.get(i));
            if(i==body.acsa.size()-1?op!=RETURN:op!=DEFINE&&op!=SET&&op!=MOVE&&op!=CALL)return false;
        }
        return true;
    }
    private Integer allocate(int namespace) {
        for(int low=0xffff;low>1;low--) {int id=(namespace<<16)|low;if(used.add(id))return id;}
        return null;
    }
    private static boolean fixed(AcsElement value) {
        if(value instanceof AcsObject object) {
            if(object.mmp.containsKey("ref"))return false;
            return object.mmp.values().stream().allMatch(ExecSpecializer::fixed);
        }
        return !(value instanceof AcsArray array)||array.acsa.stream().allMatch(ExecSpecializer::fixed);
    }
    private static AcsElement bound(AcsElement value,AcsArray contexts) {
        if(value instanceof AcsObject object) {
            if(object.mmp.size()==1&&object.mmp.containsKey("ref"))return ExecO2.copy(contexts.acsa.get(object.getAsInt("ref")),null);
            for(String name:List.copyOf(object.mmp.keySet()))object.mmp.put(name,bound(object.mmp.get(name),contexts));
        } else if(value instanceof AcsArray array)for(int i=0;i<array.acsa.size();i++)array.acsa.set(i,bound(array.acsa.get(i),contexts));
        return value;
    }
    private static void bind(AcsElement value,AcsArray contexts) {bound(value,contexts);}
}
