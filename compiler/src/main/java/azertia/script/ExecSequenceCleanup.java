package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Local cleanup of inliner-owned scalar temporaries; no CFG or effectful
 * expression is moved, and failed constant operations stay at their source. */
final class ExecSequenceCleanup {
    private final Set<Integer> temporaries=new HashSet<>();
    private final Map<AcsElement,int[]> positions;
    private ExecSequenceCleanup(List<AcsElement> declarations,Map<AcsElement,int[]> positions) {
        this.positions=positions;
        for(var declaration:declarations)temporaries.add(number(declaration,"v"));
    }
    static int optimize(AcsArray body,List<AcsElement> declarations,Map<AcsElement,int[]> positions,int firstSlot) {
        var pass=new ExecSequenceCleanup(declarations,positions);
        pass.visit(body,null);
        Set<Integer> reads=new HashSet<>();pass.references(body,reads,true);
        pass.removeUnused(body,reads);
        Set<Integer> used=new HashSet<>();pass.references(body,used,false);
        declarations.removeIf(item->!used.contains(number(item,"v")));
        Map<Integer,Integer> slots=new HashMap<>();int next=firstSlot;
        for(var declaration:declarations)slots.put(number(declaration,"v"),next++);
        pass.rename(body,slots);
        for(var declaration:declarations)pass.rename(declaration,slots);
        return next;
    }
    private AcsElement visit(AcsElement value,Map<Integer,AcsElement> known) {
        int op=opcode(value);
        if(op==VARIABLE&&known!=null&&known.containsKey(number(value,"v")))
            return ExecO2.copy(known.get(number(value,"v")),positions);
        if(op==SEQUENCE) {
            var state=known==null?new HashMap<Integer,AcsElement>():known;
            var sequence=(AcsObject)value;var statements=sequence.getAsAcsArray("statements");
            for(int i=0;i<statements.acsa.size();i++)statements.acsa.set(i,visit(statements.acsa.get(i),state));
            sequence.put("value",visit(field(value,"value"),state));
            return value;
        }
        if(known!=null&&(op==SELECT||op==AND||op==OR)) {
            // Facts entering a lazy arm are valid, but arm writes cannot leak
            // into another arm or into code following the conditional.
            String condition=op==SELECT?"v":"v1";
            ((AcsObject)value).put(condition,visit(field(value,condition),known));
            for(String arm:op==SELECT?List.of("then","else"):List.of("v2"))
                ((AcsObject)value).put(arm,visit(field(value,arm),new HashMap<>(known)));
            known.clear();return fold(value);
        }
        if(known!=null&&(op==BLOCK||op==IF||op==WHILE||op==CLEANUP))known.clear();
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                &&child.role()!=ExecShape.Role.STORAGE_TARGET)
            ExecOptimizer.replace(value,child,visit(child.value(),known));
        if(known==null)return value;
        if(op==SET||op==MOVE) {
            int slot=op==SET?number(value,"v"):number(field(value,"v1"),"v");
            known.entrySet().removeIf(entry->entry.getKey()==slot
                    ||opcode(entry.getValue())==VARIABLE&&number(entry.getValue(),"v")==slot);
            AcsElement rhs=field(value,op==SET?"val":"v2");
            if(temporaries.contains(slot)&&leaf(rhs)&&!(opcode(rhs)==VARIABLE&&number(rhs,"v")==slot))known.put(slot,rhs);
        }
        return fold(value);
    }
    private AcsElement fold(AcsElement value) {
        AcsElement expression=value;int op=opcode(value);
        if(op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED) {
            expression=ExecO2.copy(value,null);
            ((AcsObject)expression).put("c",switch(op) {
                case ADD_I32_UNCHECKED -> ADD;case SUBTRACT_I32_UNCHECKED -> SUBTRACT;
                case MULTIPLY_I32_UNCHECKED -> MULTIPLY;default -> SHIFT_LEFT;
            });
        }
        AcsElement folded=ExecOptimizer.fold(expression);
        if(folded==expression)return value;
        return ExecO2.locate(folded,value,positions);
    }
    private static boolean leaf(AcsElement value) {
        return value instanceof AcsIntegerElement||value instanceof AcsBooleanElement||opcode(value)==VARIABLE;
    }
    private void references(AcsElement value,Set<Integer> result,boolean readsOnly) {
        int op=opcode(value);
        if(op==VARIABLE||!readsOnly&&(op==SET||op==DEFINE))result.add(number(value,"v"));
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                &&(!readsOnly||child.role()!=ExecShape.Role.STORAGE_TARGET))references(child.value(),result,readsOnly);
    }
    private AcsElement removeUnused(AcsElement value,Set<Integer> reads) {
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            ExecOptimizer.replace(value,child,removeUnused(child.value(),reads));
        if(opcode(value)!=SEQUENCE)return value;
        var sequence=(AcsObject)value;var statements=sequence.getAsAcsArray("statements");
        statements.acsa.removeIf(statement->opcode(statement)==SET&&temporaries.contains(number(statement,"v"))
                &&!reads.contains(number(statement,"v"))&&leaf(field(statement,"val")));
        // Retain the sequence as the call-site anchor even for a total result:
        // nested inline records may still refer to this logical parent frame.
        return value;
    }
    private void rename(AcsElement value,Map<Integer,Integer> slots) {
        int op=opcode(value);
        if((op==VARIABLE||op==SET||op==DEFINE)&&slots.containsKey(number(value,"v")))
            ((AcsObject)value).put("v",slots.get(number(value,"v")).intValue());
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            rename(child.value(),slots);
    }
}
