package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;

/** Cuts eager expression work into sibling statements without crossing lazy or scope boundaries. */
final class AbdLinearizer {
    static final class LimitExceeded extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        final AcsElement node;
        LimitExceeded(String message,AcsElement node) { super(message);this.node=node; }
    }
    private record Capture(int slot,boolean owning) {}
    private static final String OWNERSHIP_HINT="; this cut would change object lifetime or copying; store this operand in a local variable first";
    private static final class Statement {
        final int level;
        final AcsElement anchor;
        final List<AcsElement> prefix=new ArrayList<>();
        final List<Capture> captures=new ArrayList<>();
        Statement(int level,AcsElement anchor) { this.level=level;this.anchor=anchor; }
    }
    private final FunctionTables tables;
    private final int limit;
    private final Set<Integer> privateSlots=new HashSet<>();
    private AcsElement activeStatement;
    AbdLinearizer(FunctionTables tables,int limit) { this.tables=tables;this.limit=limit; }

    AcsElement function(AcsElement body) { return blockOrStatement(body,4); }
    AcsElement standalone(AcsElement expression) {
        if(expression instanceof AcsArray)return blockOrStatement(expression,1);
        if(!fits(expression,1))throw new LimitExceeded("Expression requires statement-level spilling; store this operand in a local variable first",expression);
        ExecShape.requireFits(expression,1,limit);return expression;
    }
    private boolean fits(AcsElement expression,int level) {
        if(FunctionTables.opcode(expression)==ExecShape.INTERNAL_SEQUENCE)return false;
        try { return (long)level+ExecShape.height(expression)<=limit; }
        catch(IllegalArgumentException error) {
            if(error.getMessage().contains("internal sequence"))return false;
            throw error;
        }
    }
    private LimitExceeded failure(AcsElement node,String hint) {
        return new LimitExceeded("Expression nesting exceeds the ABD limit of "+limit+" levels"+hint,activeStatement==null?node:activeStatement);
    }
    private void require(AcsElement node,int level,String hint) {
        if(!fits(node,level))throw failure(node,hint);
    }
    private AcsElement blockOrStatement(AcsElement body,int level) {
        if(body instanceof AcsArray block) {
            List<AcsElement> statements=new ArrayList<>();
            for(AcsElement child:block.acsa)statements.addAll(statement(child,level+2));
            block.acsa=statements;require(block,level,"");return block;
        }
        if(fits(body,level))return statement(body,level).get(0);
        // A single control body needs a scope to contain its new sibling statements.
        AcsArray block=new AcsArray();tables.inherit(block,body);
        block.acsa.addAll(statement(body,level+2));require(block,level,"");return block;
    }
    private List<AcsElement> statement(AcsElement value,int level) {
        AcsElement previous=activeStatement;activeStatement=value;
        java.util.function.Supplier<AcsElement> fallback=tables.ladders.remove(value);
        int next=tables.nextSlot;Set<Integer> priorSlots=fallback==null?Set.of():new HashSet<>(privateSlots);
        try { return lowerStatement(value,level); }
        catch(LimitExceeded error) {
            if(fallback==null)throw error;
            tables.nextSlot=next;tables.slots.keySet().removeIf(slot->slot>=next);privateSlots.clear();privateSlots.addAll(priorSlots);
            AcsElement flattened=fallback.get();return lowerStatement(flattened,level);
        }
        finally { activeStatement=previous; }
    }
    private List<AcsElement> lowerStatement(AcsElement value,int level) {
        if(value instanceof AcsArray)return List.of(blockOrStatement(value,level));
        Statement state=new Statement(level,value);
        int opcode=FunctionTables.opcode(value);
        if(opcode==ExecShape.INTERNAL_SEQUENCE&&!stagedSequence((AcsObject)value)) {
            AcsObject sequence=(AcsObject)value;List<AcsElement> output=new ArrayList<>();
            for(AcsElement part:sequence.getAsAcsArray("statements").acsa)output.addAll(statement(part,level));
            output.addAll(statement(sequence.mmp.get("v"),level));return output;
        }
        if(value instanceof AcsObject node) {
            if(opcode==ExecOpcodes.IF) {
                node.put("v",condition(node.mmp.get("v"),level+1,state));
                node.put("val",blockOrStatement(node.mmp.get("val"),level+1));
                if(node.mmp.containsKey("else"))node.put("else",blockOrStatement(node.mmp.get("else"),level+1));
            } else if(opcode==ExecOpcodes.WHILE) {
                require(node.mmp.get("v"),level+1,"; store this condition in a local variable first");
                node.put("val",blockOrStatement(node.mmp.get("val"),level+1));
            } else if(opcode==ExecOpcodes.CLEANUP) {
                node.put("v",blockOrStatement(node.mmp.get("v"),level+1));
                node.put("val",blockOrStatement(node.mmp.get("val"),level+1));
            } else value=reduce(value,level,state);
        } else value=reduce(value,level,state);
        require(value,level,"");
        List<AcsElement> output=new ArrayList<>(state.prefix);
        if((opcode==ExecOpcodes.RETURN||opcode==ExecOpcodes.RETURN_OBJECT||opcode==ExecOpcodes.RETURN_TYPED)
                &&state.captures.stream().anyMatch(Capture::owning)) {
            AcsObject returned=(AcsObject)value;String field=opcode==ExecOpcodes.RETURN_TYPED?"v":"r";
            if(returned.mmp.containsKey(field)) {
                AcsElement result=returned.mmp.get(field);boolean owning=tables.owningResult(result)||opcode!=ExecOpcodes.RETURN;
                if(owning&&!freshOwned(result))throw failure(result,OWNERSHIP_HINT);
                int slot=tables.allocate(owning);privateSlots.add(slot);
                AcsObject definition=define(slot,result);tables.inherit(definition,state.anchor);output.add(definition);
                dropCaptures(state,output);
                AcsObject read=read(slot,owning);tables.inherit(read,result);returned.put(field,read);
                require(definition,level,"");require(returned,level,"");output.add(returned);return output;
            }
        }
        output.add(value);dropCaptures(state,output);return output;
    }
    private void dropCaptures(Statement state,List<AcsElement> output) {
        List<AcsElement> drops=new ArrayList<>();
        for(int i=state.captures.size()-1;i>=0;i--) {
            Capture capture=state.captures.get(i);if(!capture.owning())continue;
            AcsObject drop=control(ExecOpcodes.DROP);drop.put("v",read(capture.slot(),false));tables.inherit(drop,state.anchor);drops.add(drop);
        }
        if(!drops.isEmpty()) { AcsElement cleanup=cleanupDrops(drops,0,drops.size());require(cleanup,state.level,"");output.add(cleanup); }
    }
    private AcsElement cleanupDrops(List<AcsElement> drops,int first,int end) {
        if(end-first==1)return drops.get(first);
        int middle=(first+end)>>>1;AcsObject cleanup=control(ExecOpcodes.CLEANUP);
        cleanup.put("v",cleanupDrops(drops,first,middle));cleanup.put("val",cleanupDrops(drops,middle,end));cleanup.put("on-error",false);tables.inherit(cleanup,drops.get(first));return cleanup;
    }
    private AcsElement reduce(AcsElement value,int level,Statement state) {
        if(fits(value,level))return value;
        if(tables.atomic.contains(value))throw failure(value,"; store this operand in a local variable first");
        if(value instanceof AcsArray)throw failure(value,"; store this operand in a local variable first");
        if(!(value instanceof AcsObject node))throw failure(value,"; statements are nested too deeply here; move inner blocks into a function");
        int opcode=FunctionTables.opcode(node);
        if(opcode==ExecShape.INTERNAL_SEQUENCE) {
            for(AcsElement part:node.getAsAcsArray("statements").acsa) {
                if(tables.stagedCaptures.contains(part)) {
                    AcsObject definition=(AcsObject)part;capture(definition.mmp.get("val"),state,false,null,definition.getAsInt("v"));
                } else state.prefix.addAll(statement(part,state.level));
            }
            return reduce(node.mmp.get("v"),level,state);
        }
        if(opcode==ExecOpcodes.BLOCK_ADDRESS) {
            retainReceivers(node,state);require(node,level,"; store this operand in a local variable first");return node;
        }
        if(opcode==ExecOpcodes.MOVE&&memoryTarget(node.mmp.get("v1"))&&!fits(node.mmp.get("v2"),level+1)&&effects(node.mmp.get("v2")))
            throw failure(node,"; store RHS in a local variable first");
        if(opcode==ExecOpcodes.IF)
            node.put("v",condition(node.mmp.get("v"),level+1,state));
        if(typedMember(node)) {
            AcsObject address=(AcsObject)node.getAsAcsArray("param").acsa.get(0);
            AcsElement base=reduce(address.mmp.get("v"),state.level+1,state);
            address.put("v",capture(base,state,false));
            require(node,level,"; store this operand in a local variable first");return node;
        }
        List<ExecShape.Child> pending=new ArrayList<>();
        for(ExecShape.Child child:ExecShape.children(node)) {
            if(child.role()==ExecShape.Role.CONTEXT||child.role()==ExecShape.Role.METHOD_ENTRY)continue;
            if(child.role()==ExecShape.Role.LAZY_VALUE) {
                require(child.value(),level+child.relativeLevel(),"; store this operand in a local variable first");continue;
            }
            if(child.role()==ExecShape.Role.LOOP_CONDITION) {
                require(child.value(),level+child.relativeLevel(),"; store this condition in a local variable first");continue;
            }
            if(child.role()==ExecShape.Role.BLOCK)throw failure(child.value(),"; store this operand in a local variable first");
            if(child.role()==ExecShape.Role.STORAGE_TARGET) {
                boolean validate=opcode==ExecOpcodes.MOVE&&!fits(node.mmp.get("v2"),level+1);
                freezeStorage((AcsObject)child.value(),state,validate);continue;
            }
            if(!fits(child.value(),level+child.relativeLevel())) {
                freeze(node,pending,state);pending.clear();
                AcsElement reduced=reduce(child.value(),state.level+1,state);replace(node,child,reduced);
                if(!fits(reduced,level+child.relativeLevel()))replace(node,child,capture(reduced,state,false,materializes(node,child)?node:null));
            }
            pending.add(child);
            if(fits(node,level))return node;
        }
        require(node,level,"; store this operand in a local variable first");return node;
    }
    private void freeze(AcsObject parent,List<ExecShape.Child> pending,Statement state) {
        for(ExecShape.Child child:pending) {
            AcsElement value=current(parent,child);
            if(constant(value)||privateRead(value))continue;
            if(FunctionTables.opcode(parent)==ExecOpcodes.BUFFER_OP&&"v".equals(child.field())) {
                if(!freshOwned(value))throw failure(value,OWNERSHIP_HINT);
                replace(parent,child,capture(value,state,true));
            } else replace(parent,child,capture(reduce(value,state.level+1,state),state,false,materializes(parent,child)?parent:null));
        }
    }
    private void freezeStorage(AcsObject target,Statement state,boolean validate) {
        if(FunctionTables.opcode(target)==ExecOpcodes.VARIABLE)return;
        AcsArray arguments=target.getAsAcsArray("param");AcsElement address=arguments.acsa.get(0);
        if(FunctionTables.opcode(address)==ExecOpcodes.OBJECT_ADDRESS) {
            AcsObject typed=(AcsObject)address;
            // Keep OA directly inside mem_get: its offset is relative to an object start.
            typed.put("v",capture(reduce(typed.mmp.get("v"),state.level+1,state),state,false));
        } else arguments.acsa.set(0,capture(reduce(address,state.level+1,state),state,false));
        if(validate) { require(target,state.level,"");state.prefix.add(target); }
    }
    private AcsElement capture(AcsElement value,Statement state,boolean retain) { return capture(value,state,retain,null); }
    private boolean stagedSequence(AcsObject sequence) {
        for(AcsElement part:sequence.getAsAcsArray("statements").acsa)if(tables.stagedCaptures.contains(part))return true;return false;
    }
    private AcsElement capture(AcsElement value,Statement state,boolean retain,AcsObject consumer) {return capture(value,state,retain,consumer,-1);}
    private AcsElement capture(AcsElement value,Statement state,boolean retain,AcsObject consumer,int stagedSlot) {
        AcsElement original=value;
        if(tables.atomic.contains(value))throw failure(value,"; store this operand in a local variable first");
        value=retainReceivers(value,state);
        value=reduce(value,state.level+1,state);boolean owning=tables.owningResult(value);
        if(owning&&!freshOwned(value)&&consumer==null)throw failure(value,OWNERSHIP_HINT);
        if(callerTemporaries(value))throw failure(value,OWNERSHIP_HINT);
        if(owning&&unsafeLater(state.anchor,original,retain?null:consumer))throw failure(value,OWNERSHIP_HINT);
        int slot=stagedSlot<0?tables.allocate(owning):stagedSlot;privateSlots.add(slot);
        AcsObject definition=define(slot,value);tables.inherit(definition,value);tables.inherit(definition,state.anchor);
        require(definition,state.level,"");state.prefix.add(definition);state.captures.add(new Capture(slot,owning));
        AcsObject read=read(slot,owning&&!retain);tables.inherit(read,value);return read;
    }
    /** IF's condition() ends its owned temporaries before entering either branch. */
    private AcsElement condition(AcsElement value,int level,Statement outer) {
        Statement local=new Statement(outer.level,value);
        AcsElement result=reduce(value,level,local);
        if(local.captures.stream().anyMatch(Capture::owning)) {
            if(!fits(result,local.level+3))result=capture(result,local,false);
            AcsObject inner=control(ExecOpcodes.NOT);inner.put("v",result);tables.inherit(inner,value);
            AcsObject checked=control(ExecOpcodes.NOT);checked.put("v",inner);tables.inherit(checked,value);
            int slot=tables.allocate(false);privateSlots.add(slot);
            AcsObject saved=define(slot,checked);tables.inherit(saved,value);require(saved,local.level,"");local.prefix.add(saved);
            dropCaptures(local,local.prefix);result=read(slot,false);tables.inherit(result,value);
        }
        outer.prefix.addAll(local.prefix);return result;
    }
    /** Retain fresh value receivers whose addresses would otherwise outlive a capture statement. */
    private AcsElement retainReceivers(AcsElement value,Statement state) {
        if(!(value instanceof AcsObject node))return value;
        if(FunctionTables.opcode(node)==ExecOpcodes.BLOCK_ADDRESS||FunctionTables.opcode(node)==ExecOpcodes.BUFFER_OP) {
            AcsElement receiver=node.mmp.get("v");
            if(freshOwned(receiver))node.put("v",capture(receiver,state,true));
            else if(!borrowedOwner(receiver)||needsRetention(receiver))throw failure(node,OWNERSHIP_HINT);
            if(FunctionTables.opcode(node)==ExecOpcodes.BLOCK_ADDRESS)return node;
        }
        List<ExecShape.Child> pending=new ArrayList<>();
        for(ExecShape.Child child:ExecShape.children(node)) {
            if(child.role()==ExecShape.Role.CONTEXT||child.role()==ExecShape.Role.METHOD_ENTRY)continue;
            if(conditionChild(node,child)||FunctionTables.opcode(node)==ExecOpcodes.BUFFER_OP&&"v".equals(child.field()))continue;
            if(needsRetention(child.value())) {
                if(child.role()!=ExecShape.Role.EAGER_VALUE)throw failure(child.value(),"; store this operand in a local variable first");
                freeze(node,pending,state);pending.clear();replace(node,child,retainReceivers(child.value(),state));
            }
            if(child.role()==ExecShape.Role.EAGER_VALUE)pending.add(child);
        }
        return node;
    }
    private boolean needsRetention(AcsElement value) {
        if(!(value instanceof AcsObject node))return false;
        if((FunctionTables.opcode(node)==ExecOpcodes.BLOCK_ADDRESS||FunctionTables.opcode(node)==ExecOpcodes.BUFFER_OP)&&freshOwned(node.mmp.get("v")))return true;
        for(ExecShape.Child child:ExecShape.children(node))
            if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY&&!conditionChild(node,child)&&needsRetention(child.value()))return true;
        return false;
    }
    private static boolean conditionChild(AcsObject parent,ExecShape.Child child) {
        int opcode=FunctionTables.opcode(parent);
        return (opcode==ExecOpcodes.IF||opcode==ExecOpcodes.WHILE)&&"v".equals(child.field());
    }
    private boolean freshOwned(AcsElement value) {
        if(!(value instanceof AcsObject node)||!tables.owningResult(value))return false;
        if(node.getAsInt("t")==1)return tables.scriptIds.contains(node.getAsInt("id"));
        return switch(FunctionTables.opcode(node)) {
            case ExecOpcodes.NEW_BLOCK,ExecOpcodes.MOVE_OUT,ExecOpcodes.BUFFER_NEW,ExecOpcodes.CALL_VIRTUAL -> true;
            case ExecOpcodes.BUFFER_OP -> node.getAsInt("op")==3;
            case ExecOpcodes.CHECK_TYPE,ExecOpcodes.CHECK_OBJECT -> freshOwned(node.mmp.get("v"));
            case ExecOpcodes.SELECT -> freshOwned(node.mmp.get("then"))&&freshOwned(node.mmp.get("else"));
            case ExecOpcodes.IF -> node.mmp.containsKey("else")&&freshOwned(node.mmp.get("val"))&&freshOwned(node.mmp.get("else"));
            default -> false;
        };
    }
    private boolean borrowedOwner(AcsElement value) {
        if(!(value instanceof AcsObject node))return false;
        if(node.getAsInt("t")==1)return node.getAsInt("id")==0x0abd0006;
        return switch(FunctionTables.opcode(node)) {
            case ExecOpcodes.VARIABLE,ExecOpcodes.SET,ExecOpcodes.MOVE -> true;
            case ExecOpcodes.CHECK_TYPE,ExecOpcodes.CHECK_OBJECT -> borrowedOwner(node.mmp.get("v"));
            case ExecOpcodes.SELECT -> borrowedOwner(node.mmp.get("then"))&&borrowedOwner(node.mmp.get("else"));
            default -> false;
        };
    }
    private static boolean materializes(AcsObject parent,ExecShape.Child child) {
        int opcode=FunctionTables.opcode(parent);
        return parent.getAsInt("t")==1&&"param".equals(child.field())
                ||opcode==ExecOpcodes.CALL_VIRTUAL&&"param".equals(child.field())
                ||opcode==ExecOpcodes.BUFFER_OP&&"args".equals(child.field())
                ||opcode==ExecOpcodes.VALUE_COMPARE;
    }
    /** These operands create caller-statement temporaries that cannot end at a new prefix. */
    private boolean callerTemporaries(AcsElement value) {
        if(value instanceof AcsArray block) {for(AcsElement child:block.acsa)if(callerTemporaries(child))return true;return false;}
        if(!(value instanceof AcsObject node))return false;
        for(ExecShape.Child child:ExecShape.children(node)) {
            if(child.role()==ExecShape.Role.CONTEXT||child.role()==ExecShape.Role.METHOD_ENTRY)continue;
            if(materializes(node,child)&&tables.owningResult(child.value())
                    &&!(node.getAsInt("t")==1&&tables.scriptIds.contains(node.getAsInt("id")))
                    &&FunctionTables.opcode(node)!=ExecOpcodes.CALL_VIRTUAL)return true;
            if(callerTemporaries(child.value()))return true;
        }
        return false;
    }
    /** A retained owner must finish before a later returned automatic pointer on error. */
    private boolean unsafeLater(AcsElement anchor,AcsElement source,AcsObject consumer) {
        boolean[] state={false,false};return unsafeLater(anchor,source,consumer,state);
    }
    private boolean unsafeLater(AcsElement value,AcsElement source,AcsObject consumer,boolean[] state) {
        if(state[1])return false;
        if(value==source){state[0]=true;return false;}
        if(value instanceof AcsArray block) {for(AcsElement child:block.acsa)if(unsafeLater(child,source,consumer,state))return true;return false;}
        if(!(value instanceof AcsObject node))return false;
        for(ExecShape.Child child:ExecShape.children(node))
            if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                    &&unsafeLater(child.value(),source,consumer,state))return true;
        if(node==consumer){if(state[0])state[1]=true;return false;}
        if(!state[0]||state[1])return false;
        if(node.getAsInt("t")==1) {
            int id=node.getAsInt("id"),abi=tables.returns.getOrDefault(id,6);
            return !(tables.scopeSafeCalls.contains(id)&&abi>=0&&abi<=5)
                    &&id!=0x0abd0000&&id!=0x0abd0006&&!(id>=0x0abd000b&&id<=0x0abd0013);
        }
        int opcode=FunctionTables.opcode(node);
        return opcode==ExecOpcodes.CALL_VIRTUAL
                ||opcode==ExecOpcodes.OBJECT_DELETE||opcode==ExecOpcodes.BUFFER_NEW;
    }
    private boolean privateRead(AcsElement value) {
        return FunctionTables.opcode(value)==ExecOpcodes.VARIABLE&&privateSlots.contains(((AcsObject)value).getAsInt("v"));
    }
    private static boolean constant(AcsElement value) { return !(value instanceof AcsObject)&&!(value instanceof AcsArray); }
    private static boolean typedMember(AcsObject node) {
        return node.getAsInt("t")==1&&node.getAsInt("id")==0x0abd0006&&node.getAsAcsArray("param").acsa.size()==1
                &&FunctionTables.opcode(node.getAsAcsArray("param").acsa.get(0))==ExecOpcodes.OBJECT_ADDRESS;
    }
    private static boolean memoryTarget(AcsElement value) {
        return value instanceof AcsObject node&&node.getAsInt("t")==1&&node.getAsInt("id")==0x0abd0006;
    }
    private static boolean effects(AcsElement value) {
        if(value instanceof AcsArray block) { for(AcsElement child:block.acsa)if(effects(child))return true;return false; }
        if(!(value instanceof AcsObject node))return false;
        if(node.getAsInt("t")==1)return true;
        int opcode=FunctionTables.opcode(node);
        if(opcode==5||opcode==6||opcode==10||opcode==11||opcode==34||opcode==35||opcode==37||opcode==40||opcode==52||opcode==53
                ||opcode==41&&Set.of(2,4,5,6).contains(node.getAsInt("op")))return true;
        for(ExecShape.Child child:ExecShape.children(node))
            if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY&&effects(child.value()))return true;
        return false;
    }
    private static AcsObject control(int opcode) { AcsObject node=new AcsObject();node.put("t",0);node.put("c",opcode);return node; }
    private static AcsObject read(int slot,boolean move) { AcsObject node=control(move?ExecOpcodes.MOVE_OUT:ExecOpcodes.VARIABLE);node.put("v",slot);return node; }
    private static AcsObject define(int slot,AcsElement value) { AcsObject node=control(ExecOpcodes.DEFINE);node.put("v",slot);node.put("val",value);return node; }
    private static AcsElement current(AcsObject parent,ExecShape.Child child) {
        return child.index()<0?parent.mmp.get(child.field()):parent.getAsAcsArray(child.field()).acsa.get(child.index());
    }
    private static void replace(AcsObject parent,ExecShape.Child child,AcsElement value) {
        if(child.index()<0)parent.put(child.field(),value);else parent.getAsAcsArray(child.field()).acsa.set(child.index(),value);
    }
}
