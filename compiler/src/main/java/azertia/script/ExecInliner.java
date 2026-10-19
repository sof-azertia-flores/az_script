package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;

/** Bounded, non-trapping scalar leaf inlining. Arguments occur exactly once in
 * their original order, so no temporary, alias or ownership protocol is lost. */
final class ExecInliner {
    private final Map<Integer,AcsObject> functions = new HashMap<>();
    private final Map<AcsElement,int[]> positions;
    private int expanded;
    private ExecInliner(AcsArray functions, Map<AcsElement,int[]> positions) {
        for(var item:functions.acsa) { var function=(AcsObject)item; this.functions.put(function.getAsInt("id"),function); }
        this.positions=positions;
    }
    static void optimize(AcsArray functions, Map<AcsElement,int[]> positions) {
        var pass=new ExecInliner(functions,positions);
        for(var item:functions.acsa) {
            var function=(AcsObject)item;pass.expanded=0;
            var body=ExecIr.read(function.mmp.get("script"),positions);
            function.mmp.put("script",pass.visit(body));
        }
    }
    private int type(AcsElement value) {
        var node=ExecIr.read(value,null);
        if(node.opcode()==VARIABLE)return ANY_VALUE; // A nominal slot type is not a runtime proof after host/alias mutation.
        if(node.opcode()==CALL) {
            var function=functions.get(((AcsObject)value).getAsInt("id"));
            return function==null?ANY_VALUE:function.getAsInt("return-type");
        }
        if(node.opcode()==CHECK_TYPE) {
            var context=((AcsObject)value).getAsAcsObject("context");
            return context.mmp.containsKey("abi")?context.getAsInt("abi"):ANY_VALUE;
        }
        return node.type();
    }
    private AcsElement visit(ExecIr.Node node) {
        AcsElement source=node.source();
        for(var edge:node.children())ExecOptimizer.replace(source,edge.location(),visit(edge.value()));
        if(node.opcode()==CHECK_TYPE) {
            var check=(AcsObject)source;var context=check.getAsAcsObject("context");var value=check.mmp.get("v");
            if(context.mmp.containsKey("abi")&&scalar(context.getAsInt("abi"))&&context.getAsInt("abi")==type(value))return value;
        }
        if(node.opcode()!=CALL||expanded>=256)return source;
        var call=(AcsObject)source;var function=functions.get(call.getAsInt("id"));
        if(function==null||function.getAsInt("entry-kind")!=0||function.getAsInt("local-count")!=0)return source;
        AcsElement rawBody=function.mmp.get("script");
        List<AcsElement> body=rawBody instanceof AcsArray block?block.acsa:List.of(rawBody);
        if(body.size()!=1||!(body.get(0) instanceof AcsObject returned)||returned.getAsInt("t")!=0)return source;
        int opcode=returned.getAsInt("c");
        var arguments=call.getAsAcsArray("param").acsa;
        var parameterTypes=function.getAsAcsArray("param-types").acsa;
        if(arguments.size()!=parameterTypes.size()||arguments.size()>4)return source;
        int resultType=function.getAsInt("return-type");
        AcsElement expression;
        if(opcode==RETURN_TYPED) {
            // Scalar generic identity specialization, keyed by the actual fixed
            // context. No generated function IDs or unbounded version cache.
            if(function.getAsInt("hidden-count")!=1||arguments.size()!=1||!call.mmp.containsKey("contexts"))return source;
            var contexts=call.getAsAcsArray("contexts").acsa;
            if(contexts.size()!=1||!(contexts.get(0) instanceof AcsObject context)||!context.mmp.containsKey("abi"))return source;
            var reference=returned.getAsAcsObject("context");
            if(!reference.mmp.containsKey("ref")||reference.getAsInt("ref")!=0)return source;
            resultType=context.getAsInt("abi");expression=returned.mmp.get("v");
            if(((AcsIntegerElement)parameterTypes.get(0)).s!=ANY_VALUE || !(expression instanceof AcsObject variable)
                    || variable.getAsInt("t")!=0 || variable.getAsInt("c")!=VARIABLE || variable.getAsInt("v")!=0)return source;
            if(!scalar(resultType)||type(arguments.get(0))!=resultType)return source;
        } else {
            if(opcode!=RETURN||function.getAsInt("hidden-count")!=0||!scalar(resultType))return source;
            expression=returned.mmp.get("r");
            for(int i=0;i<arguments.size();++i) {
                int expected=((AcsIntegerElement)parameterTypes.get(i)).s;
                if(!scalar(expected)||type(arguments.get(i))!=expected)return source;
            }
        }
        if(expression==null)return source;
        var order=new ArrayList<Integer>();
        AcsElement replacement=substitute(expression,arguments,order,source,0);
        if(replacement==null||order.size()!=arguments.size())return source;
        for(int i=0;i<order.size();++i)if(order.get(i)!=i)return source;
        if(type(replacement)!=resultType)return source;
        if(ExecShape.height(replacement)>ExecShape.height(source))return source;
        ++expanded;return replacement;
    }
    private static boolean scalar(int type){return type==INT_VALUE||type==BOOLEAN_VALUE;}
    private AcsElement substitute(AcsElement source,List<AcsElement> arguments,List<Integer> order,AcsElement call,int depth) {
        if(depth>8)return null;
        if(source instanceof AcsIntegerElement integer)return new AcsIntegerElement(integer.s);
        if(source instanceof AcsBooleanElement bool)return new AcsBooleanElement(bool.on);
        if(!(source instanceof AcsObject object)||object.getAsInt("t")!=0)return null;
        int opcode=object.getAsInt("c");
        if(opcode==VARIABLE) {
            int slot=object.getAsInt("v");if(slot<0||slot>=arguments.size())return null;
            order.add(slot);return arguments.get(slot);
        }
        if(!(opcode>=ADD&&opcode<=LESS_EQUAL||opcode==NOT||opcode==NEGATE||opcode>=BIT_AND&&opcode<=SHIFT_UNSIGNED))return null;
        AcsObject result=new AcsObject();result.mmp.putAll(object.mmp);
        for(var child:ExecShape.children(object)) {
            AcsElement value=substitute(child.value(),arguments,order,call,depth+1);
            if(value==null)return null;ExecOptimizer.replace(result,child,value);
        }
        AcsElement folded=ExecOptimizer.fold(result);
        if(folded==result) {
            // Nonconstant arithmetic and shifts may fail inside the callee;
            // retaining the call preserves its error frame and source site.
            if(opcode==NOT) {if(type(result.mmp.get("v"))!=BOOLEAN_VALUE)return null;}
            else if(opcode>=BIT_AND&&opcode<=BIT_XOR) {
                if(type(result.mmp.get("v1"))!=INT_VALUE||type(result.mmp.get("v2"))!=INT_VALUE)return null;
            } else if(opcode>=GREATER&&opcode<=LESS_EQUAL) {
                int left=type(result.mmp.get("v1")),right=type(result.mmp.get("v2"));
                if(left!=right||!scalar(left)||(left==BOOLEAN_VALUE&&opcode!=EQUAL&&opcode!=NOT_EQUAL))return null;
            } else return null;
        }
        if(positions!=null&&positions.containsKey(call))positions.put(folded,positions.get(call));
        return folded;
    }
}
