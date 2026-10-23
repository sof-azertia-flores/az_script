package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Development-v11 stages. The O0/O1/O2 pipelines never enter these rewrites. */
final class ExecO3 {
    static boolean enabled(Set<Compiler.OptimizationPass> passes) {
        return passes.stream().anyMatch(pass -> pass.ordinal() >= SEQUENCE_INLINING.ordinal());
    }
    static void optimize(AcsObject program, Set<Compiler.OptimizationPass> passes,
                         Map<AcsElement,int[]> positions, Map<Integer,ExecSpecializer.GenericSignature> genericSignatures, int abdLimit) {
        if (!enabled(passes)) return;
        AcsArray functions=program.getAsAcsArray("f");
        if(passes.contains(SCALAR_REPLACEMENT)) ExecOptimizationCost.run(program,positions,abdLimit,()-> {
            var originalBodies=new ArrayList<AcsElement>();
            for(var item:functions.acsa)originalBodies.add(((AcsObject)item).mmp.get("script"));
            ExecScalarReplacement.optimize(functions,passes,positions,abdLimit);
            boolean changed=false;
            for(int i=0;i<functions.acsa.size();i++)changed|=originalBodies.get(i)!=((AcsObject)functions.acsa.get(i)).mmp.get("script");
            if(changed)ExecO2.optimize(functions,passes,positions,abdLimit);
        });
        if(passes.contains(CONSTANT_SPECIALIZATION)||passes.contains(CONTEXT_SPECIALIZATION))
            ExecOptimizationCost.run(program,positions,abdLimit,()->ExecSpecializer.optimize(program,passes,positions,genericSignatures,abdLimit));
        Map<Integer,Integer> returns=new HashMap<>();
        for(var item:functions.acsa) {
            var function=(AcsObject)item;returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        for(var item:program.getAsAcsArray("extern-signatures").acsa) {
            var function=(AcsObject)item;returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        // Facts are computed on the original structured CFG, before introducing seq.
        if(passes.contains(UNCHECKED_ARITHMETIC)) ExecOptimizationCost.run(program,positions,abdLimit,()-> {
            Set<Integer> safe=safeFunctions(functions);
            for(var item:functions.acsa) {
                var function=(AcsObject)item;
                if(safe.contains(function.getAsInt("id")))unchecked(function,returns);
            }
        });
        if(passes.contains(SEQUENCE_INLINING))
            ExecOptimizationCost.run(program,positions,abdLimit,()->ExecSequenceInliner.optimize(functions,returns,positions,abdLimit));
        ExecOptimizationCost.run(program,positions,abdLimit,()->ExecObjectOptimizer.optimize(functions,passes,positions,abdLimit));
        if(passes.contains(SLOT_COMPACTION)) ExecOptimizationCost.run(program,positions,abdLimit,()-> {
            Set<Integer> safe=safeFunctions(functions);
            for(var item:functions.acsa) {
                var function=(AcsObject)item;
                if(safe.contains(function.getAsInt("id"))&&scalarDefinitions(function.mmp.get("script")))new Slots(function).compact();
            }
        });
    }
    private static void unchecked(AcsObject function,Map<Integer,Integer> returns) {
        var analysis=new ExecScalarAnalysis(function,returns);
        for(var site:analysis.blocks)if(site.source!=null&&site.in!=null) {
            AcsElement value=site.source;
            int op=opcode(value);
            if(op==IF||op==WHILE)continue;
            if(op==DEFINE||op==SET)uncheckedExpression(field(value,"val"),site.in,analysis);
            else if(op==MOVE)uncheckedExpression(field(value,"v2"),site.in,analysis);
            else if(op==RETURN)uncheckedExpression(field(value,"r"),site.in,analysis);
            else uncheckedExpression(value,site.in,analysis);
        }
    }
    private static void uncheckedExpression(AcsElement value,Map<Integer,Value> facts,ExecScalarAnalysis analysis) {
        if(value==null)return;
        int op=opcode(value),replacement=-1;
        // Rewrite children first so a proven shift contributes its narrowed interval.
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
            uncheckedExpression(child.value(),facts,analysis);
        if(op==ADD||op==SUBTRACT||op==MULTIPLY) {
            Value result=analysis.evaluate(value,facts);
            if(result.type()==INT_VALUE&&result.total())
                replacement=op==ADD?ADD_I32_UNCHECKED:op==SUBTRACT?SUBTRACT_I32_UNCHECKED:MULTIPLY_I32_UNCHECKED;
        } else if(op==SHIFT_LEFT) {
            Value a=analysis.evaluate(field(value,"v1"),facts),b=analysis.evaluate(field(value,"v2"),facts);
            if(a.type()==INT_VALUE&&b.type()==INT_VALUE&&a.total()&&b.total()&&b.low()>=0&&b.high()<=31) {
                long factor=1L<<(int)b.high();
                if(a.low()*factor>=Integer.MIN_VALUE&&a.high()*factor<=Integer.MAX_VALUE)replacement=SHIFT_LEFT_I32_UNCHECKED;
            }
        }
        if(replacement!=-1)((AcsObject)value).put("c",replacement);
    }
    private static boolean scalarDefinitions(AcsElement value) {
        if(opcode(value)==DEFINE&&(!((AcsObject)value).mmp.containsKey("declared-type")||!scalar(number(value,"declared-type"))))return false;
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                &&!scalarDefinitions(child.value()))return false;
        return true;
    }
    /** Reuses a typed slot only after its entire lexical scope has exited. */
    private static final class Slots {
        final AcsObject function;
        final Map<Integer,Integer> renamed=new HashMap<>();
        final Map<Integer,TreeSet<Integer>> free=new HashMap<>();
        int next;
        Slots(AcsObject function) {this.function=function;next=function.getAsInt("param-count");}
        void compact() {
            allocate(function.mmp.get("script"),new ArrayList<>());
            rewrite(function.mmp.get("script"));function.put("local-count",next-function.getAsInt("param-count"));
        }
        void allocate(AcsElement value,List<int[]> owner) {
            if(opcode(value)==BLOCK) {
                var declared=new ArrayList<int[]>();
                for(var child:((AcsArray)value).acsa)allocate(child,declared);
                for(var slot:declared)free.computeIfAbsent(slot[1],ignored->new TreeSet<>()).add(slot[0]);
                return;
            }
            if(opcode(value)==DEFINE) {
                int type=number(value,"declared-type");var slots=free.computeIfAbsent(type,ignored->new TreeSet<>());
                int slot=slots.isEmpty()?next++:slots.pollFirst();renamed.put(number(value,"v"),slot);owner.add(new int[]{slot,type});
            }
            for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
                allocate(child.value(),owner);
        }
        void rewrite(AcsElement value) {
            int op=opcode(value);
            if(op==VARIABLE||op==DEFINE||op==SET) {
                int slot=number(value,"v");
                if(renamed.containsKey(slot))((AcsObject)value).put("v",renamed.get(slot).intValue());
            }
            for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)
                rewrite(child.value());
        }
    }
}
