package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Field scalar replacement for closed regions with a proven, nonthrowing lifecycle.
 * The initial implementation admits only int/bool fields, straight-line field
 * initialization, empty destructors/method tables and field-returning methods.
 * Every address use must be consumed here. Original allocations, copies and
 * cleanup remain: block IDs and the heap's free ranges/length are observable.
 * All other shapes remain unchanged. */
final class ExecScalarReplacement {
    private static final int MEM_GET=0x0abd0006,ALLOCATE=0x0abd0014;
    private static final class Refused extends RuntimeException {}
    private record Factory(int storageType,int width,List<Integer> types,AcsObject constructor) {}
    private record ObjectSlots(Factory factory,int[] slots) {}
    private final Map<Integer,AcsObject> functions;
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,ObjectSlots> objects=new HashMap<>();
    private final Map<Integer,Integer> locals=new HashMap<>();
    private int next;
    private boolean changed;

    private ExecScalarReplacement(Map<Integer,AcsObject> functions,Map<AcsElement,int[]> positions,AcsObject function) {
        this.functions=functions;this.positions=positions;
        int params=function.getAsInt("param-count");next=params+function.getAsInt("local-count");
        var types=function.getAsAcsArray("param-types").acsa;
        for(int i=0;i<params;i++)locals.put(i,((AcsIntegerElement)types.get(i)).s);
    }
    static void optimize(AcsArray functions,Set<Compiler.OptimizationPass> passes,
                         Map<AcsElement,int[]> positions,int abdLimit) {
        if(!passes.contains(Compiler.OptimizationPass.SCALAR_REPLACEMENT))return;
        Map<Integer,AcsObject> table=new HashMap<>();
        for(var item:functions.acsa) {var f=(AcsObject)item;table.put(f.getAsInt("id"),f);}
        for(var item:functions.acsa) {
            var function=(AcsObject)item;
            if(function.getAsInt("entry-kind")!=0||function.getAsInt("hidden-count")!=0)continue;
            var pass=new ExecScalarReplacement(table,positions,function);
            var original=function.mmp.get("script");
            try {
                var result=pass.rewrite(ExecO2.copy(original,positions));
                if(pass.changed&&pass.next<=256&&count(result)<=MAX_NODES&&4L+ExecShape.height(result)<=abdLimit) {
                    function.mmp.put("script",result);
                    function.put("local-count",pass.next-function.getAsInt("param-count"));
                }
            } catch(Refused ignored) { /* A whole-function proof, with atomic fallback. */ }
        }
    }
    private static void require(boolean condition) {if(!condition)throw new Refused();}
    private static boolean scalarType(int type) {return type==INT_VALUE||type==BOOLEAN_VALUE;}
    private static int declaredType(AcsElement value) {
        var type=((AcsObject)value).mmp.get("declared-type");
        return type instanceof AcsIntegerElement?((AcsIntegerElement)type).s:ANY_VALUE;
    }
    private static int variable(AcsElement value) {
        require(opcode(value)==VARIABLE);return number(value,"v");
    }
    private static boolean noContexts(AcsObject call) {
        return !call.mmp.containsKey("contexts")||call.getAsAcsArray("contexts").acsa.isEmpty();
    }
    private static AcsElement addressBase(AcsElement value) {
        if(opcode(value)==BLOCK_ADDRESS)value=field(value,"v");
        return value;
    }
    private static int fieldIndex(AcsElement value,int receiver) {
        if(opcode(value)!=CALL||number(value,"id")!=MEM_GET||!noContexts((AcsObject)value))return -1;
        var args=((AcsObject)value).getAsAcsArray("param").acsa;
        if(args.size()!=1||opcode(args.get(0))!=OBJECT_ADDRESS)return -1;
        AcsElement address=args.get(0),base=addressBase(field(address,"v"));
        if(opcode(base)!=VARIABLE||number(base,"v")!=receiver)return -1;
        return number(address,"offset");
    }
    private static boolean receiverCheck(AcsElement value,int receiver) {
        return opcode(value)==OBJECT_ADDRESS&&number(value,"offset")==0
                &&opcode(field(value,"v"))==VARIABLE&&number(field(value,"v"),"v")==receiver;
    }
    private static boolean emptyMethods(AcsElement value,int receiver) {
        return opcode(value)==SET_METHODS&&opcode(field(value,"v"))==VARIABLE
                &&number(field(value,"v"),"v")==receiver&&((AcsObject)value).getAsAcsArray("entries").acsa.isEmpty();
    }
    private void emptyDestructor(AcsObject bind) {
        require(!bind.getAsBool("manual")&&noContexts(bind));
        if(!bind.mmp.containsKey("destructor"))return;
        var destructor=functions.get(bind.getAsInt("destructor"));require(destructor!=null);
        require(destructor.getAsInt("param-count")==1&&destructor.getAsInt("hidden-count")==0
                &&destructor.getAsInt("return-type")==VOID_VALUE&&destructor.getAsInt("entry-kind")!=2
                &&((AcsIntegerElement)destructor.getAsAcsArray("param-types").acsa.get(0)).s==ADDRESS_VALUE);
        var body=destructor.mmp.get("script");require(body instanceof AcsArray);
        for(var statement:((AcsArray)body).acsa)require(receiverCheck(statement,0)||emptyMethods(statement,0));
    }
    private Factory factory(AcsObject call) {
        require(noContexts(call));var factory=functions.get(call.getAsInt("id"));require(factory!=null);
        require(factory.getAsInt("hidden-count")==0&&factory.getAsInt("entry-kind")==1);
        var body=factory.mmp.get("script");require(body instanceof AcsArray);
        var list=((AcsArray)body).acsa;require(list.size()==4&&opcode(list.get(0))==DEFINE);
        int storage=number(list.get(0),"v");var allocation=field(list.get(0),"val");int width;
        int storageType;
        if(opcode(allocation)==NEW_BLOCK) {
            storageType=OBJECT_VALUE;width=number(allocation,"size");
        } else {
            storageType=ADDRESS_VALUE;
            require(opcode(allocation)==CALL&&number(allocation,"id")==ALLOCATE&&noContexts((AcsObject)allocation));
            var sizes=((AcsObject)allocation).getAsAcsArray("param").acsa;
            require(sizes.size()==1&&sizes.get(0) instanceof AcsIntegerElement);
            width=((AcsIntegerElement)sizes.get(0)).s;
        }
        require(factory.getAsInt("return-type")==storageType&&declaredType(list.get(0))==storageType);
        require(width>0&&width<=256);
        require(opcode(list.get(1))==CALL&&opcode(list.get(2))==OBJECT_BIND&&opcode(list.get(3))==RETURN_OBJECT);
        var invoke=(AcsObject)list.get(1);var bind=(AcsObject)list.get(2);emptyDestructor(bind);
        require(variable(addressBase(field(bind,"v")))==storage&&variable(field(list.get(3),"r"))==storage);
        require(noContexts(invoke));var arguments=invoke.getAsAcsArray("param").acsa;
        int params=factory.getAsInt("param-count");require(arguments.size()==params+1);
        require(variable(addressBase(arguments.get(0)))==storage);
        for(int i=0;i<params;i++)require(variable(arguments.get(i+1))==i);
        var constructor=functions.get(invoke.getAsInt("id"));require(constructor!=null);
        require(constructor.getAsInt("hidden-count")==0&&constructor.getAsInt("param-count")==params+1
                &&constructor.getAsInt("return-type")==VOID_VALUE&&constructor.mmp.get("script") instanceof AcsArray);
        var parameterTypes=constructor.getAsAcsArray("param-types").acsa;
        require(((AcsIntegerElement)parameterTypes.get(0)).s==ADDRESS_VALUE);
        for(int i=0;i<params;i++)require(scalarType(((AcsIntegerElement)parameterTypes.get(i+1)).s)
                &&((AcsIntegerElement)parameterTypes.get(i+1)).s==((AcsIntegerElement)factory.getAsAcsArray("param-types").acsa.get(i)).s);
        var fields=new ArrayList<Integer>(Collections.nCopies(width,-1));
        for(var statement:((AcsArray)constructor.mmp.get("script")).acsa) {
            if(receiverCheck(statement,0)||emptyMethods(statement,0))continue;
            require(opcode(statement)==MOVE);int index=fieldIndex(field(statement,"v1"),0);
            require(index>=0&&index<width);var value=field(statement,"v2");int type;
            if(value instanceof AcsIntegerElement)type=INT_VALUE;
            else if(value instanceof AcsBooleanElement)type=BOOLEAN_VALUE;
            else {int parameter=variable(value);require(parameter>0&&parameter<=params);type=((AcsIntegerElement)parameterTypes.get(parameter)).s;}
            if(fields.get(index)==-1)require(opcode(value)==CONSTANT);
            else require(fields.get(index)==type);
            fields.set(index,type);
        }
        require(!fields.contains(-1));return new Factory(storageType,width,List.copyOf(fields),constructor);
    }
    private AcsElement slot(int id,AcsElement from) {return ExecO2.variable(id,from,positions);}
    private AcsObject definition(int id,int type,AcsElement value,AcsElement from) {
        var result=new AcsObject();result.put("t",0);result.put("c",DEFINE);result.put("v",id);
        result.put("declared-type",type);result.put("val",value);locals.put(id,type);
        return (AcsObject)ExecO2.locate(result,from,positions);
    }
    private AcsObject assignment(int id,AcsElement value,AcsElement from) {
        var result=new AcsObject();result.put("t",0);result.put("c",SET);result.put("v",id);result.put("val",value);
        return (AcsObject)ExecO2.locate(result,from,positions);
    }
    private int type(AcsElement value) {
        if(value instanceof AcsIntegerElement)return INT_VALUE;
        if(value instanceof AcsBooleanElement)return BOOLEAN_VALUE;
        if(opcode(value)==VARIABLE)return locals.getOrDefault(number(value,"v"),ANY_VALUE);
        int op=opcode(value);
        if(op==NEGATE&&type(field(value,"v"))==INT_VALUE)return INT_VALUE;
        if(op==NOT&&type(field(value,"v"))==BOOLEAN_VALUE)return BOOLEAN_VALUE;
        if(op==SELECT) {
            int left=type(field(value,"then")),right=type(field(value,"else"));
            return left==right?left:ANY_VALUE;
        }
        if(op>=ADD&&op<=OR||op>=BIT_AND&&op<=SHIFT_UNSIGNED
                ||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED) {
            int left=type(field(value,"v1")),right=type(field(value,"v2"));
            if(op==AND||op==OR)return left==BOOLEAN_VALUE&&right==BOOLEAN_VALUE?BOOLEAN_VALUE:ANY_VALUE;
            if(left==INT_VALUE&&right==INT_VALUE)return op>=GREATER&&op<=LESS_EQUAL?BOOLEAN_VALUE:INT_VALUE;
            if((op==EQUAL||op==NOT_EQUAL)&&left==BOOLEAN_VALUE&&right==BOOLEAN_VALUE)return BOOLEAN_VALUE;
        }
        return ANY_VALUE;
    }
    private List<AcsElement> create(AcsObject definition) {
        int original=definition.getAsInt("v"),declared=declaredType(definition);
        var initial=definition.mmp.get("val");var result=new ArrayList<AcsElement>();Factory shape;
        if(declared==OBJECT_VALUE&&opcode(initial)==VARIABLE) {
            var source=objects.get(number(initial,"v"));require(source!=null&&source.factory.storageType==OBJECT_VALUE);
            int[] fields=new int[source.slots.length];
            // Preserve the runtime copy's allocation and ownership while the
            // independently copied scalar group contains the current fields.
            result.add(definition);
            for(int i=0;i<fields.length;i++) {
                fields[i]=next++;
                result.add(definition(fields[i],source.factory.types.get(i),slot(source.slots[i],initial),definition));
            }
            objects.put(original,new ObjectSlots(source.factory,fields));changed=true;return result;
        }
        require(opcode(initial)==CALL);shape=factory((AcsObject)initial);
        require(declared==shape.storageType);
        var arguments=((AcsObject)initial).getAsAcsArray("param").acsa;
        var paramTypes=shape.constructor.getAsAcsArray("param-types").acsa;
        require(arguments.size()+1==paramTypes.size());int[] snapshots=new int[arguments.size()];
        for(int i=0;i<arguments.size();i++) {
            var argument=rewrite(arguments.get(i));int expected=((AcsIntegerElement)paramTypes.get(i+1)).s;
            require(type(argument)==expected);snapshots[i]=next++;
            result.add(definition(snapshots[i],expected,argument,initial));
        }
        // The real call still runs at the declaration site with exactly one
        // evaluation of each argument. Its allocation and cleanup effects must
        // remain even when all subsequent field accesses become scalar.
        for(int i=0;i<arguments.size();i++)arguments.set(i,slot(snapshots[i],initial));
        result.add(definition);
        int[] fields=new int[shape.width];boolean[] initialized=new boolean[fields.length];
        for(int i=0;i<fields.length;i++)fields[i]=next++;
        for(var statement:((AcsArray)shape.constructor.mmp.get("script")).acsa) {
            if(opcode(statement)!=MOVE)continue;
            int index=fieldIndex(field(statement,"v1"),0);var value=field(statement,"v2");
            AcsElement replacement=opcode(value)==VARIABLE?slot(snapshots[number(value,"v")-1],initial):ExecO2.copy(value,positions);
            if(initialized[index])result.add(assignment(fields[index],replacement,definition));
            else {result.add(definition(fields[index],shape.types.get(index),replacement,definition));initialized[index]=true;}
        }
        objects.put(original,new ObjectSlots(shape,fields));changed=true;return result;
    }
    private ObjectSlots owner(AcsElement address) {
        var base=addressBase(address);if(opcode(base)!=VARIABLE)return null;
        return objects.get(number(base,"v"));
    }
    private AcsElement fieldRead(AcsElement value) {
        if(opcode(value)!=CALL||number(value,"id")!=MEM_GET)return null;
        var arguments=((AcsObject)value).getAsAcsArray("param").acsa;require(arguments.size()==1);
        var address=arguments.get(0);require(opcode(address)==OBJECT_ADDRESS&&noContexts((AcsObject)value));
        var object=owner(ExecScalarAnalysis.field(address,"v"));require(object!=null);
        int index=number(address,"offset");require(index>=0&&index<object.slots.length);
        return slot(object.slots[index],value);
    }
    private AcsElement method(AcsObject call) {
        require(noContexts(call));var args=call.getAsAcsArray("param").acsa;
        require(args.size()==1);var object=owner(args.get(0));require(object!=null);
        var method=functions.get(call.getAsInt("id"));require(method!=null&&method.getAsInt("param-count")==1
                &&method.getAsInt("hidden-count")==0&&method.getAsInt("entry-kind")==0
                &&((AcsIntegerElement)method.getAsAcsArray("param-types").acsa.get(0)).s==ADDRESS_VALUE);
        var body=method.mmp.get("script");require(body instanceof AcsArray);
        var statements=((AcsArray)body).acsa;require(statements.size()==2&&receiverCheck(statements.get(0),0)&&opcode(statements.get(1))==RETURN);
        var value=ExecScalarAnalysis.field(statements.get(1),"r");int index=fieldIndex(value,0);
        require(index>=0&&index<object.slots.length&&method.getAsInt("return-type")==object.factory.types.get(index));
        return slot(object.slots[index],call);
    }
    private AcsElement rewrite(AcsElement value) {
        int op=opcode(value);
        if(op==BLOCK) {
            var block=(AcsArray)value;var result=new ArrayList<AcsElement>();
            for(var statement:block.acsa) {
                if(opcode(statement)==DEFINE) {
                    var definition=(AcsObject)statement;var initial=definition.mmp.get("val");
                    int type=declaredType(definition);
                    if((type==ADDRESS_VALUE||type==OBJECT_VALUE)
                            &&(opcode(initial)==CALL&&functions.containsKey(number(initial,"id"))
                            ||type==OBJECT_VALUE&&opcode(initial)==VARIABLE)) {
                        result.addAll(create(definition));continue;
                    }
                }
                result.add(rewrite(statement));
            }
            block.acsa.clear();block.acsa.addAll(result);return block;
        }
        if(op==CALL) {var field=fieldRead(value);return field!=null?field:method((AcsObject)value);}
        if(op==VARIABLE)require(number(value,"v")>=0&&!objects.containsKey(number(value,"v")));
        if(op==DEFINE) {
            int type=declaredType(value);require(scalarType(type));locals.put(number(value,"v"),type);
        }
        if(op==SET)require(number(value,"v")>=0&&!objects.containsKey(number(value,"v")));
        require(op==CONSTANT||op==BLOCK||op==VARIABLE||op==DEFINE||op==SET||op==MOVE||op==RETURN||op==IF||op==WHILE
                ||op==BREAK||op==CONTINUE||op>=ADD&&op<=NEGATE||op>=BIT_AND&&op<=SELECT
                ||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED);
        for(var child:ExecShape.children(value)) {
            require(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY);
            ExecOptimizer.replace(value,child,rewrite(child.value()));
        }
        // A nominal field/local declaration alone is not a runtime value
        // proof. The closed region must preserve its scalar tag at writes.
        if(op==DEFINE||op==SET)require(type(field(value,"val"))==locals.getOrDefault(number(value,"v"),ANY_VALUE)
                &&scalarType(type(field(value,"val"))));
        if(op==MOVE)require(opcode(field(value,"v1"))==VARIABLE&&scalarType(type(field(value,"v1")))
                &&type(field(value,"v1"))==type(field(value,"v2")));
        return value;
    }
}
