package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;

/** Speculates only total int/bool operations in isolated scalar functions.
 * Ownership-bearing functions are deliberately excluded by the escape/effect
 * analysis, including calls that could publish an address or change a slot. */
final class ExecLoopMotion {
    enum Escape { NONE, LOCAL_IDENTITY, ESCAPES, UNKNOWN }
    record Ownership(Escape escape, boolean scriptReentry, boolean cleanup) {}
    static Ownership ownership(ExecIr.Node node) {
        // BLOCK's scope protocol alone does not create or export an object.
        Escape escape=Escape.NONE;boolean calls=false,cleans=false;
        for(var edge:node.children()) {
            var child=ownership(edge.value());
            if(child.escape.ordinal()>escape.ordinal())escape=child.escape;
            calls|=child.scriptReentry;cleans|=child.cleanup;
        }
        if(node.opcode()!=BLOCK) {
            calls|=node.ownEffects().contains(ExecIr.Effect.CALL);
            cleans|=node.ownEffects().contains(ExecIr.Effect.CLEANUP);
            Escape own=switch(node.opcode()) {
                case NEW_BLOCK,BUFFER_NEW,OBJECT_BIND -> Escape.LOCAL_IDENTITY;
                case RETURN_OBJECT,RETURN_TYPED,MOVE_OUT -> Escape.ESCAPES;
                case CALL,CALL_VIRTUAL,BUFFER_OP,MOVE,SET_METHODS,CHECK_TYPE,CHECK_OBJECT -> Escape.UNKNOWN;
                default -> Escape.NONE;
            };
            if(own.ordinal()>escape.ordinal())escape=own;
        }
        return new Ownership(escape,calls,cleans);
    }
    private final Map<Integer,Integer> types=new HashMap<>();
    private final Map<AcsElement,int[]> positions;
    private int nextSlot,hoisted;
    private ExecLoopMotion(Map<AcsElement,int[]> positions){this.positions=positions;}
    static void optimize(AcsObject function,Map<AcsElement,int[]> positions) {
        var pass=new ExecLoopMotion(positions);
        var params=function.getAsAcsArray("param-types").acsa;
        if(function.getAsInt("hidden-count")!=0 || params.stream().anyMatch(value ->
                ((AcsIntegerElement)value).s!=INT_VALUE && ((AcsIntegerElement)value).s!=BOOLEAN_VALUE))return;
        for(int i=0;i<params.size();++i)pass.types.put(i,((AcsIntegerElement)params.get(i)).s);
        var body=ExecIr.read(function.mmp.get("script"),positions);pass.types(body);
        if(ownership(body).escape()==Escape.ESCAPES || !pass.isolated(body))return;
        pass.nextSlot=function.getAsInt("param-count")+function.getAsInt("local-count");
        pass.blocks(body,new HashSet<>(pass.parameterSlots(params.size())));
        function.put("local-count",pass.nextSlot-function.getAsInt("param-count"));
    }
    private Set<Integer> parameterSlots(int count){Set<Integer> result=new HashSet<>();for(int i=0;i<count;++i)result.add(i);return result;}
    private void types(ExecIr.Node node) {
        if(node.opcode()==DEFINE&&node.declaredType()!=ANY_VALUE)types.put(node.slot(),node.declaredType());
        for(var edge:node.children())types(edge.value());
    }
    private boolean scalarSlot(Integer slot) {return slot!=null&&slot>=0&&(types.getOrDefault(slot,ANY_VALUE)==INT_VALUE||types.getOrDefault(slot,ANY_VALUE)==BOOLEAN_VALUE);}
    private boolean isolated(ExecIr.Node node) {
        if(node.opcode()==VARIABLE&&!scalarSlot(node.slot()))return false;
        if(node.opcode()==MOVE) {
            var target=node.child("v1");if(target.opcode()!=VARIABLE||!scalarSlot(target.slot()))return false;
        } else if(node.opcode()!=BLOCK&&node.opcode()!=DEFINE&&node.opcode()!=SET&&(node.ownEffects().contains(ExecIr.Effect.CALL)
                ||node.ownEffects().contains(ExecIr.Effect.WRITE_HEAP)||node.ownEffects().contains(ExecIr.Effect.CLEANUP)))return false;
        if((node.opcode()==DEFINE||node.opcode()==SET)&&!scalarSlot(node.slot()))return false;
        for(var edge:node.children())if(!isolated(edge.value()))return false;
        return true;
    }
    private void writes(ExecIr.Node node,Set<Integer> result) {
        if(node.opcode()==DEFINE||node.opcode()==SET||node.opcode()==MOVE_OUT)result.add(node.slot());
        if(node.opcode()==MOVE)result.add(node.child("v1").slot());
        for(var edge:node.children())writes(edge.value(),result);
    }
    private void blocks(ExecIr.Node node,Set<Integer> live) {
        if(node.opcode()!=BLOCK) {
            for(var edge:node.children())blocks(edge.value(),new HashSet<>(live));
            return;
        }
        AcsArray block=(AcsArray)node.source();List<AcsElement> output=new ArrayList<>();
        for(var edge:node.children()) {
            ExecIr.Node child=edge.value();
            if(child.opcode()==WHILE&&hoisted<64) {
                Set<Integer> stable=new HashSet<>(live),modified=new HashSet<>();writes(child,modified);stable.removeAll(modified);
                Map<String,Integer> cached=new HashMap<>();
                motion(child,stable,cached,output,false);
            }
            blocks(ExecIr.read(child.source(),positions),new HashSet<>(live));
            output.add(child.source());
            if(child.opcode()==DEFINE)live.add(child.slot());
        }
        block.acsa.clear();block.acsa.addAll(output);
    }
    private int totalType(ExecIr.Node node,Set<Integer> stable) {
        if(node.opcode()==CONSTANT)return node.scalarConstant()?node.type():ANY_VALUE;
        if(node.opcode()==VARIABLE)return stable.contains(node.slot())?types.getOrDefault(node.slot(),ANY_VALUE):ANY_VALUE;
        int opcode=node.opcode();
        if(opcode==NOT)return totalType(node.child("v"),stable)==BOOLEAN_VALUE?BOOLEAN_VALUE:ANY_VALUE;
        if(opcode< GREATER || opcode>OR && (opcode<BIT_AND||opcode>SHIFT_UNSIGNED))return ANY_VALUE;
        int left=totalType(node.child("v1"),stable),right=totalType(node.child("v2"),stable);
        if(left!=right)return ANY_VALUE;
        if(opcode>=BIT_AND&&opcode<=BIT_XOR)return left==INT_VALUE?INT_VALUE:ANY_VALUE;
        if(opcode>=SHIFT_LEFT&&opcode<=SHIFT_UNSIGNED) {
            AcsElement count=node.child("v2").source();
            return left==INT_VALUE&&count instanceof AcsIntegerElement value&&value.s>=0&&value.s<=31?INT_VALUE:ANY_VALUE;
        }
        if(opcode==AND||opcode==OR)return left==BOOLEAN_VALUE?BOOLEAN_VALUE:ANY_VALUE;
        return left==INT_VALUE||left==BOOLEAN_VALUE&&(opcode==EQUAL||opcode==NOT_EQUAL)?BOOLEAN_VALUE:ANY_VALUE;
    }
    private void motion(ExecIr.Node node,Set<Integer> stable,Map<String,Integer> cached,List<AcsElement> prefix,boolean storage) {
        for(var edge:node.children()) {
            var child=edge.value();boolean target=edge.location().role()==ExecShape.Role.STORAGE_TARGET;
            int type=target?ANY_VALUE:totalType(child,stable);
            if(!storage&&!target&&child.opcode()!=VARIABLE&&child.opcode()!=CONSTANT&&type!=ANY_VALUE
                    &&hoisted<64&&nextSlot<ExecCodec.MAX_SLOTS) {
                String key=child.source().toJson().toString();Integer slot=cached.get(key);
                if(slot==null) {
                    slot=nextSlot++;++hoisted;types.put(slot,type);cached.put(key,slot);
                    AcsObject definition=new AcsObject();definition.put("t",0);definition.put("c",DEFINE);
                    definition.put("v",slot.intValue());definition.put("declared-type",type);definition.put("val",child.source());
                    locate(definition,child.source());prefix.add(definition);
                }
                AcsObject variable=new AcsObject();variable.put("t",0);variable.put("c",VARIABLE);variable.put("v",slot.intValue());
                locate(variable,child.source());ExecOptimizer.replace(node.source(),edge.location(),variable);
            } else motion(child,stable,cached,prefix,target);
        }
    }
    private void locate(AcsElement value,AcsElement source){if(positions!=null&&positions.containsKey(source))positions.put(value,positions.get(source));}
}
