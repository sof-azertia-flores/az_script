package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.JsonElement;
import java.util.*;

/** Per-function lowering metadata; none of it is serialized into the exec. */
final class FunctionTables {
    final Map<Integer,Integer> returns;
    Set<Integer> scriptIds=Set.of();
    Set<Integer> scopeSafeCalls=Set.of();
    final Set<AcsElement> atomic=Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<Integer,Integer> slots = new HashMap<>();
    final IdentityHashMap<AcsElement,JsonElement> origins = new IdentityHashMap<>();
    final IdentityHashMap<AcsElement,Boolean> results = new IdentityHashMap<>();
    final IdentityHashMap<AcsElement,int[]> positions;
    int nextSlot;

    FunctionTables(Map<Integer,Integer> returns, IdentityHashMap<AcsElement,int[]> positions) {
        this.returns = returns; this.positions = positions;
    }
    void inherit(AcsElement generated, AcsElement original) {
        JsonElement source = origins.get(original);
        if (source != null) origins.putIfAbsent(generated,source);
        if (positions != null) {
            int[] position = positions.get(original);
            if (position != null) positions.putIfAbsent(generated,position);
        }
    }
    int allocate(boolean owning) {
        if (nextSlot >= ExecCodec.MAX_SLOTS) throw new IllegalArgumentException("Function frame exceeds " + ExecCodec.MAX_SLOTS + " variable slots");
        int slot=nextSlot++; slots.put(slot,owning?8:0); return slot;
    }
    static int opcode(AcsElement value) {
        return value instanceof AcsObject object && object.mmp.get("t") instanceof AcsIntegerElement kind && kind.s==0
                && object.mmp.get("c") instanceof AcsIntegerElement code ? code.s : Integer.MIN_VALUE;
    }
    boolean owningResult(AcsElement value) {
        Boolean known=results.get(value); if(known!=null)return known;
        if(value instanceof AcsArray block)return !block.acsa.isEmpty()&&owningResult(block.acsa.get(block.acsa.size()-1));
        if(!(value instanceof AcsObject node))return false;
        if(node.mmp.get("t") instanceof AcsIntegerElement kind && kind.s==1)
            return owningAbi(returns.getOrDefault(node.getAsInt("id"),6));
        return switch(opcode(node)) {
            case ExecOpcodes.VARIABLE -> owningAbi(slots.getOrDefault(node.getAsInt("v"),6));
            case ExecOpcodes.NEW_BLOCK,ExecOpcodes.MOVE_OUT,ExecOpcodes.BUFFER_NEW -> true;
            case ExecOpcodes.SET -> owningAbi(slots.getOrDefault(node.getAsInt("v"),6));
            case ExecOpcodes.MOVE -> owningResult(node.mmp.get("v1"));
            case ExecOpcodes.CHECK_TYPE,ExecOpcodes.CHECK_OBJECT -> owningResult(node.mmp.get("v"));
            case ExecOpcodes.CALL_VIRTUAL -> owningAbi(node.getAsInt("type"));
            case ExecOpcodes.BUFFER_OP -> node.getAsInt("op")==3; // Unknown element kinds are conservatively owning.
            case ExecOpcodes.IF -> owningResult(node.mmp.get("val"))||node.mmp.containsKey("else")&&owningResult(node.mmp.get("else"));
            case ExecOpcodes.SELECT -> owningResult(node.mmp.get("then"))||owningResult(node.mmp.get("else"));
            case ExecShape.INTERNAL_SEQUENCE -> owningResult(node.mmp.get("v"));
            case 4,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,28,29,30,31,33,35,36,38,42,43,44,45,46,47,48,49,52 -> false;
            default -> true;
        };
    }
    private static boolean owningAbi(int type) { return type==6||type==8; }
}
