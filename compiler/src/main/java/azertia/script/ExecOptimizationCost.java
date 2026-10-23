package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Shared admission budget for O3 rewrites. Native costs are conservative
 * estimates for ordinary (non-stress) lowering, not a replacement for the
 * runtime's authoritative MIR/resume limits or the measured performance gate. */
final class ExecOptimizationCost {
    static final int NATIVE_OPERATIONS=4096, NATIVE_RESUME_CELLS=8192;
    record Cost(int nodes,int slots,int height,long vmOperations,long nativeOperations,long resumeCells) {}
    private record Body(long vm,long nativeOps,long leaders,int registers) {}

    static Cost measure(AcsObject function) {
        var script=function.mmp.get("script");
        int slots=function.getAsInt("param-count")+function.getAsInt("local-count");
        var body=body(script,true);
        return new Cost(count(script),slots,ExecShape.height(script),body.vm+1,body.nativeOps+body.leaders+1,
                (body.leaders+1)*(slots+body.registers+3L));
    }
    private static boolean growsBeyond(long before,long after,long limit) {
        return after>limit&&after>before;
    }
    static boolean accepts(Cost before,Cost after,int abdLimit) {
        return !growsBeyond(before.nodes,after.nodes,MAX_NODES)
                &&!growsBeyond(before.slots,after.slots,256)
                &&!growsBeyond(before.height,after.height,abdLimit-4L)
                &&!growsBeyond(before.nativeOperations,after.nativeOperations,NATIVE_OPERATIONS)
                &&!growsBeyond(before.resumeCells,after.resumeCells,NATIVE_RESUME_CELLS);
    }
    /** Every stage has one atomic per-function fallback, including debug names,
     * inline chains and slot counts. A rejected newly introduced private entry
     * rolls back the whole stage so that rewritten callers cannot dangle. */
    static void run(AcsObject program,Map<AcsElement,int[]> positions,int abdLimit,Runnable rewrite) {
        var functions=program.getAsAcsArray("f");
        var snapshots=new LinkedHashMap<Integer,AcsObject>();
        var costs=new HashMap<Integer,Cost>();
        for(var item:functions.acsa) {
            var function=(AcsObject)item;int id=function.getAsInt("id");
            snapshots.put(id,(AcsObject)ExecO2.copy(function,positions));costs.put(id,measure(function));
        }
        rewrite.run();
        for(var item:functions.acsa) {
            var function=(AcsObject)item;int id=function.getAsInt("id");
            if(!snapshots.containsKey(id)&&!accepts(new Cost(0,0,0,0,0,0),measure(function),abdLimit)) {
                functions.acsa.clear();functions.acsa.addAll(snapshots.values());return;
            }
        }
        for(var item:functions.acsa) {
            var function=(AcsObject)item;int id=function.getAsInt("id");
            if(costs.containsKey(id)&&!accepts(costs.get(id),measure(function),abdLimit)) {
                function.mmp.clear();function.mmp.putAll(snapshots.get(id).mmp);
            }
        }
    }
    private static Body body(AcsElement value,boolean root) {
        int op=opcode(value),children=0,registers=1;
        long vm=1,nativeOps=1,leaders=0;
        boolean reuse=op==BLOCK||op==SEQUENCE||op==IF||op==WHILE||op==SELECT||op==AND||op==OR||op==CLEANUP;
        int offset=op==SET?2:1;
        for(var child:ExecShape.children(value)) {
            if(child.role()==ExecShape.Role.CONTEXT||child.role()==ExecShape.Role.METHOD_ENTRY)continue;
            var part=body(child.value(),false);children++;
            vm+=part.vm;nativeOps+=part.nativeOps;leaders+=part.leaders;
            registers=Math.max(registers,offset+part.registers);
            if(!reuse)offset++;
        }
        switch(op) {
            case CONSTANT -> {vm++;nativeOps+=3;}
            case VARIABLE -> {vm++;nativeOps++;}
            case BLOCK -> {
                vm+=1+3L*children+(root?0:2);nativeOps+=3+children+(root?0:2);
                // branch_flow makes both its target and successor leaders.
                leaders+=children+1L;
            }
            case SEQUENCE -> {vm++;nativeOps+=2;}
            case DEFINE -> {vm+=3;nativeOps+=6;}
            case SET -> {vm+=2;nativeOps+=5;}
            case MOVE -> {vm++;nativeOps+=4;}
            case RETURN,RETURN_OBJECT -> {vm+=2;nativeOps+=5;}
            case CALL,CALL_VIRTUAL -> {vm+=1+children;nativeOps+=1+3L*children;leaders+=2;}
            case IF,SELECT -> {vm+=6;nativeOps+=8;leaders+=4;}
            case WHILE -> {vm+=10;nativeOps+=9;leaders+=5;}
            case AND,OR -> {vm+=5;nativeOps+=8;leaders+=3;}
            case BREAK,CONTINUE -> {vm+=2;nativeOps+=4;}
            case CLEANUP -> {vm+=8;nativeOps+=8;leaders+=6;}
            case INCREMENT_I32_UNCHECKED -> {vm+=5;nativeOps+=12;registers+=3;}
            default -> {
                if(op>=ADD&&op<=LESS_EQUAL||op>=BIT_AND&&op<=SHIFT_UNSIGNED
                        ||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED) {
                    vm+=2;nativeOps+=8;
                } else if(op==NOT||op==NEGATE||op==CONVERT) {vm+=2;nativeOps+=6;}
                else {vm+=2;nativeOps+=3;leaders+=2;}
            }
        }
        return new Body(vm,nativeOps,leaders,registers);
    }
}
