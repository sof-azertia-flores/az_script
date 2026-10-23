package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.Compiler.AsTypes.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Object-region optimizations that never speculate a potentially failing read.
 * A field becomes available only after its successful scalar assignment. Calls,
 * heap writes, cleanup and loop edges invalidate it before any later reuse. */
final class ExecObjectOptimizer {
    private static final int MEM_GET = 0x0abd0006;
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,Integer> types = new HashMap<>();
    private final Map<Integer,AcsObject> methods;
    private final Map<Integer,AcsObject> functions;
    private final Set<Integer> scalarFunctions;
    private final boolean cacheFields, devirtualize;
    private record Cached(int slot, Set<Integer> dependencies) {}

    private ExecObjectOptimizer(Map<AcsElement,int[]> positions, Map<Integer,AcsObject> methods,
                                Map<Integer,AcsObject> functions, Set<Integer> scalarFunctions, boolean cacheFields, boolean devirtualize) {
        this.positions=positions; this.methods=methods; this.functions=functions;
        this.scalarFunctions=scalarFunctions;
        this.cacheFields=cacheFields; this.devirtualize=devirtualize;
    }
    static void optimize(AcsArray functions, Set<Compiler.OptimizationPass> passes,
                         Map<AcsElement,int[]> positions, int abdLimit) {
        if(!passes.contains(FIELD_CACHING)&&!passes.contains(DEVIRTUALIZATION))return;
        Map<Integer,AcsObject> table=new HashMap<>(), candidates=new HashMap<>();
        Set<Integer> ambiguous=new HashSet<>();
        for(var item:functions.acsa) {
            var function=(AcsObject)item; table.put(function.getAsInt("id"),function);
            methods(function.mmp.get("script"),candidates,ambiguous);
        }
        ambiguous.forEach(candidates::remove);
        Set<Integer> scalarFunctions=safeFunctions(functions);
        for(var item:functions.acsa) {
            var function=(AcsObject)item;
            var pass=new ExecObjectOptimizer(positions,candidates,table,scalarFunctions,
                    passes.contains(FIELD_CACHING),passes.contains(DEVIRTUALIZATION));
            var params=function.getAsAcsArray("param-types").acsa;
            for(int i=0;i<params.size();i++)pass.types.put(i,((AcsIntegerElement)params.get(i)).s);
            AcsElement original=function.mmp.get("script"); pass.types(original);
            AcsElement body=ExecO2.copy(original,positions);
            if(pass.devirtualize)body=new DirectCalls(table,positions,functions).visit(body,new DirectCalls.State(function),false);
            body=pass.visit(body,new HashMap<>(),false);
            int before=count(original),after=count(body);
            // Field reuse only shrinks. Guarded calls must fit the common
            // analysis envelope without turning a small function into a large one.
            if(4L+ExecShape.height(body)<=abdLimit
                    &&(after<=before||after<=MAX_NODES&&after<=before+Math.max(64,before/4)))
                function.mmp.put("script",body);
        }
    }
    private static void methods(AcsElement value, Map<Integer,AcsObject> methods, Set<Integer> ambiguous) {
        if(opcode(value)==SET_METHODS) {
            var entries=((AcsObject)value).getAsAcsArray("entries").acsa;
            for(int slot=0;slot<entries.size();slot++) {
                var entry=(AcsObject)entries.get(slot);
                if(!entry.getAsAcsArray("contexts").acsa.isEmpty()) {ambiguous.add(slot);continue;}
                var previous=methods.putIfAbsent(slot,entry);
                if(previous!=null&&previous.getAsInt("id")!=entry.getAsInt("id"))ambiguous.add(slot);
            }
        }
        for(var child:ExecShape.children(value))if(code(child))methods(child.value(),methods,ambiguous);
    }
    private static boolean code(ExecShape.Child child) {
        return child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY;
    }
    private void types(AcsElement value) {
        if(opcode(value)==DEFINE) {
            var definition=(AcsObject)value;
            types.put(definition.getAsInt("v"),definition.mmp.containsKey("declared-type")
                    ?definition.getAsInt("declared-type"):ANY_VALUE);
        }
        for(var child:ExecShape.children(value))if(code(child))types(child.value());
    }
    private boolean scalarSlot(int slot) {return slot>=0&&scalar(types.getOrDefault(slot,ANY_VALUE));}
    private static void forget(Map<String,Cached> available,int slot) {
        available.entrySet().removeIf(entry->entry.getValue().slot==slot||entry.getValue().dependencies.contains(slot));
    }
    private static void dependencies(AcsElement value,Set<Integer> slots) {
        if(opcode(value)==VARIABLE)slots.add(number(value,"v"));
        for(var child:ExecShape.children(value))if(code(child))dependencies(child.value(),slots);
    }
    private static boolean stableAddress(AcsElement value) {
        int op=opcode(value);
        if(op==VARIABLE)return number(value,"v")>=0;
        if(op==OBJECT_ADDRESS||op==BLOCK_ADDRESS)return stableAddress(field(value,"v"));
        return false;
    }
    private static boolean fieldRead(AcsElement value) {
        if(opcode(value)!=CALL||number(value,"id")!=MEM_GET)return false;
        var arguments=((AcsObject)value).getAsAcsArray("param").acsa;
        // A plain raw address is not a field identity. Object address nodes
        // retain their live-object/range validation on the first read.
        return arguments.size()==1&&(opcode(arguments.get(0))==OBJECT_ADDRESS||opcode(arguments.get(0))==BLOCK_ADDRESS)
                &&stableAddress(arguments.get(0));
    }
    private static boolean scalarFieldRead(AcsElement value) {
        if(!(value instanceof AcsObject))return false;
        int op=opcode(value);
        // Declared field/local types are not runtime proofs: a trusted host
        // can install an owned value. Reusing its copy would change object
        // identity and cleanup. Only a successful scalar type/conversion
        // check lets this pass cache the result of a field read.
        if(op==CONVERT&&scalar(number(value,"type")))return fieldRead(field(value,"v"));
        if(op==CHECK_TYPE) {
            var context=((AcsObject)value).getAsAcsObject("context");
            return context.mmp.containsKey("abi")&&scalar(context.getAsInt("abi"))&&fieldRead(field(value,"v"));
        }
        return false;
    }
    private AcsElement visit(AcsElement value,Map<String,Cached> available,boolean storage) {
        if(value==null)return null;
        int op=opcode(value);
        if(cacheFields&&!storage&&scalarFieldRead(value)) {
            var cached=available.get(value.toJson().toString());
            if(cached!=null)return ExecO2.variable(cached.slot,value,positions);
            return value;
        }
        if(op==BLOCK) {
            var local=new HashMap<>(available); available.clear();
            var block=(AcsArray)value;
            for(int i=0;i<block.acsa.size();i++)block.acsa.set(i,visit(block.acsa.get(i),local,false));
            return value;
        }
        if(op==IF||op==SELECT||op==AND||op==OR||op==WHILE||op==CLEANUP) {
            // Nothing is available across a backedge, exceptional cleanup,
            // conditional evaluation or a join. Each arm may still reuse its
            // own successful reads in evaluation order.
            available.clear();
            for(var child:ExecShape.children(value))if(code(child))
                ExecOptimizer.replace(value,child,visit(child.value(),new HashMap<>(),child.role()==ExecShape.Role.STORAGE_TARGET));
            return value;
        }
        if(op==DEFINE||op==SET||op==MOVE) {
            Integer target=null;
            if(op!=MOVE)target=number(value,"v");
            else if(opcode(field(value,"v1"))==VARIABLE)target=number(field(value,"v1"),"v");
            String rhs=op==MOVE?"v2":"val";
            if(op==MOVE) {
                // The storage operand is evaluated first. It may invoke a
                // callback or publish an alias; visit it even when no cache is kept.
                var object=(AcsObject)value;
                object.mmp.put("v1",visit(field(value,"v1"),available,true));
            }
            AcsElement original=field(value,rhs);
            AcsElement replacement=visit(original,available,false);
            if(replacement!=null)((AcsObject)value).mmp.put(rhs,replacement);
            // Replacing an existing local can destroy a host-injected object;
            // only a fresh definition has no previous value to clean up.
            if(op!=DEFINE||target==null||!scalarSlot(target)) {available.clear();return value;}
            forget(available,target);
            if(cacheFields&&replacement!=null&&scalarFieldRead(replacement)) {
                Set<Integer> used=new HashSet<>(); dependencies(replacement,used);
                if(!used.contains(target))available.put(replacement.toJson().toString(),new Cached(target,Set.copyOf(used)));
            }
            return value;
        }
        for(var child:ExecShape.children(value))if(code(child))
            ExecOptimizer.replace(value,child,visit(child.value(),available,child.role()==ExecShape.Role.STORAGE_TARGET));
        if(op==CALL_VIRTUAL&&devirtualize) {
            var replacement=guarded((AcsObject)value);
            available.clear();return replacement;
        }
        if(op==CALL) {
            // A nominal scalar result is insufficient. Only closed scalar
            // callees with proven primitive arguments cannot copy/destroy an
            // injected owning value or re-enter to mutate the observed object.
            var call=(AcsObject)value;
            boolean closed=scalarFunctions.contains(call.getAsInt("id"))
                    &&(!call.mmp.containsKey("contexts")||call.getAsAcsArray("contexts").acsa.isEmpty())
                    &&call.getAsAcsArray("param").acsa.stream().allMatch(arg->primitiveArgument(arg,available));
            if(number(value,"id")!=MEM_GET&&!closed)available.clear();
        } else if(op==CALL_VIRTUAL||op==VALUE_COMPARE||op==OBJECT_BIND||op==OBJECT_DELETE||op==DROP||op==MOVE_OUT||op==RETURN_OBJECT
                ||op==RETURN_TYPED||op==BUFFER_OP||op==BUFFER_NEW||op==CONTEXT_DEFAULT
                ||op==SET_METHODS||op==CHECK_TYPE||op==CHECK_OBJECT||op==INCREMENT_I32_UNCHECKED)
            available.clear();
        return value;
    }
    private boolean primitiveArgument(AcsElement value,Map<String,Cached> available) {
        if(value instanceof AcsIntegerElement||value instanceof AcsBooleanElement)return true;
        int op=opcode(value);
        if(op==VARIABLE)return available.values().stream().anyMatch(cached->cached.slot==number(value,"v"));
        if(!(op>=ADD&&op<=NEGATE||op>=BIT_AND&&op<=SELECT
                ||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED))return false;
        if(op==CONVERT&&!scalar(number(value,"type")))return false;
        return ExecShape.children(value).stream().allMatch(child->code(child)&&primitiveArgument(child.value(),available));
    }
    private static boolean pureArgument(AcsElement value) {
        int op=opcode(value);
        if(op==CONSTANT)return value instanceof AcsIntegerElement||value instanceof AcsBooleanElement||value instanceof AcsAddress;
        if(op==VARIABLE)return number(value,"v")>=0;
        if(!(op>=ADD&&op<=NEGATE||op>=BIT_AND&&op<=SELECT||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED))return false;
        for(var child:ExecShape.children(value))if(!code(child)||!pureArgument(child.value()))return false;
        return true;
    }
    private AcsElement guarded(AcsObject call) {
        var entry=methods.get(call.getAsInt("slot")); if(entry==null)return call;
        var target=functions.get(entry.getAsInt("id"));
        if(target==null||target.getAsInt("hidden-count")!=0||target.getAsInt("entry-kind")==2
                ||target.getAsInt("return-type")!=call.getAsInt("type"))return call;
        var arguments=call.getAsAcsArray("param").acsa;
        if(arguments.isEmpty()||opcode(arguments.get(0))!=VARIABLE||number(arguments.get(0),"v")<0
                ||arguments.size()!=target.getAsInt("param-count")||!arguments.stream().allMatch(ExecObjectOptimizer::pureArgument))return call;
        var guard=new AcsObject(); guard.put("t",0);guard.put("c",GUARD_METHOD);
        guard.put("receiver",ExecO2.copy(arguments.get(0),positions));guard.put("slot",call.getAsInt("slot"));
        guard.put("function",entry.getAsInt("id"));ExecO2.locate(guard,call,positions);
        var direct=new AcsObject();direct.put("t",1);direct.put("id",entry.getAsInt("id"));
        direct.put("param",ExecO2.copy(call.mmp.get("param"),positions));ExecO2.locate(direct,call,positions);
        var select=new AcsObject();select.put("t",0);select.put("c",SELECT);
        select.put("v",guard);select.put("then",direct);select.put("else",call);
        return ExecO2.locate(select,call,positions);
    }

    /** A successful, closed factory establishes an actual method table. This
     * proof never uses the variable's nominal class or a globally unique slot.
     * It is deliberately lost at aliases, callbacks and cleanup boundaries. */
    private static final class DirectCalls {
        private static final int ALLOCATE=0x0abd0014;
        private final Map<Integer,AcsObject> functions;
        private final Map<AcsElement,int[]> positions;
        private final Set<Integer> scalarFunctions;
        private final Map<Integer,Boolean> harmlessMethods=new HashMap<>();
        private final Map<Integer,List<AcsObject>> factories=new HashMap<>();
        private final Set<Integer> refusedFactories=new HashSet<>();
        private static final class State {
            final Map<Integer,List<AcsObject>> objects=new HashMap<>();
            final Set<Integer> primitives=new HashSet<>();
            State() {}
            State(AcsObject function) {
                var params=function.getAsAcsArray("param-types").acsa;
                for(int i=0;i<params.size();i++)if(scalar(((AcsIntegerElement)params.get(i)).s))primitives.add(i);
            }
            State copy() {var result=new State();result.objects.putAll(objects);result.primitives.addAll(primitives);return result;}
            void clear() {objects.clear();primitives.clear();}
        }
        DirectCalls(Map<Integer,AcsObject> functions,Map<AcsElement,int[]> positions,AcsArray all) {
            this.functions=functions;this.positions=positions;scalarFunctions=safeFunctions(all);
        }
        private static boolean noContexts(AcsObject value) {
            return !value.mmp.containsKey("contexts")||value.getAsAcsArray("contexts").acsa.isEmpty();
        }
        private static boolean variable(AcsElement value,int slot) {
            return opcode(value)==VARIABLE&&number(value,"v")==slot;
        }
        private static boolean address(AcsElement value,int slot) {
            return opcode(value)==OBJECT_ADDRESS&&number(value,"offset")==0&&variable(field(value,"v"),slot);
        }
        private static boolean scalarDefinitions(AcsElement value) {
            if(opcode(value)==DEFINE&&(!((AcsObject)value).mmp.containsKey("declared-type")||!scalar(number(value,"declared-type"))))return false;
            for(var child:ExecShape.children(value))if(code(child)&&!scalarDefinitions(child.value()))return false;
            return true;
        }
        private boolean harmless(AcsObject method) {
            return harmlessMethods.computeIfAbsent(method.getAsInt("id"),ignored->{
                // The initial loop proof covers methods that do not inspect or
                // publish this. Nominally scalar field reads can hold host-owned
                // values, so they do not establish a no-callback proof.
                var copy=(AcsObject)ExecO2.copy(method,null);
                if(!(copy.mmp.get("script") instanceof AcsArray body)||body.acsa.isEmpty()||!address(body.acsa.get(0),0))return false;
                body.acsa.remove(0);
                if(reads(body).contains(0)||!scalarDefinitions(body))return false;
                copy.getAsAcsArray("param-types").acsa.set(0,new AcsIntegerElement(INT_VALUE));
                return scalar(method.getAsInt("return-type"))&&eligible(copy,scalarFunctions);
            });
        }
        private AcsObject target(AcsObject call,State state) {
            var args=call.getAsAcsArray("param").acsa;
            if(args.isEmpty()||opcode(args.get(0))!=VARIABLE)return null;
            var entries=state.objects.get(number(args.get(0),"v"));int slot=call.getAsInt("slot");
            if(entries==null||slot<0||slot>=entries.size())return null;
            var method=functions.get(entries.get(slot).getAsInt("id"));
            if(method==null||method.getAsInt("entry-kind")!=0||method.getAsInt("hidden-count")!=0
                    ||method.getAsInt("return-type")!=call.getAsInt("type")||method.getAsInt("param-count")!=args.size()
                    ||((AcsIntegerElement)method.getAsAcsArray("param-types").acsa.get(0)).s!=ADDRESS_VALUE)return null;
            return method;
        }
        private boolean primitive(AcsElement value,State state) {
            if(value==null)return false;
            if(value instanceof AcsIntegerElement||value instanceof AcsBooleanElement)return true;
            int op=opcode(value);
            if(op==VARIABLE)return state.primitives.contains(number(value,"v"));
            if(op==CALL_VIRTUAL) {
                var call=(AcsObject)value;var method=target(call,state);
                if(method==null||!harmless(method))return false;
                var args=call.getAsAcsArray("param").acsa;
                return args.subList(1,args.size()).stream().allMatch(arg->primitive(arg,state));
            }
            if(op==CALL) {
                var call=(AcsObject)value;var function=functions.get(call.getAsInt("id"));
                if(function==null||!scalarFunctions.contains(call.getAsInt("id"))||!scalar(function.getAsInt("return-type"))||!noContexts(call))return false;
            } else if(!(op>=ADD&&op<=NEGATE||op>=BIT_AND&&op<=SELECT||op>=ADD_I32_UNCHECKED&&op<=SHIFT_LEFT_I32_UNCHECKED))return false;
            if(op==CONVERT&&!scalar(number(value,"type")))return false;
            for(var child:ExecShape.children(value))if(code(child)&&!primitive(child.value(),state))return false;
            return true;
        }
        /** A loop may retain the incoming object proof only when every path
         * consists of proven primitive storage and closed scalar work. */
        private boolean stableLoop(AcsElement value,State state) {
            int op=opcode(value);
            if(op==BLOCK) {
                for(var item:((AcsArray)value).acsa)if(!stableLoop(item,state))return false;
                return true;
            }
            if(op==DEFINE) {
                if(!((AcsObject)value).mmp.containsKey("declared-type")||!scalar(number(value,"declared-type"))||!primitive(field(value,"val"),state))return false;
                state.primitives.add(number(value,"v"));return true;
            }
            if(op==SET||op==MOVE) {
                int slot=op==SET?number(value,"v"):opcode(field(value,"v1"))==VARIABLE?number(field(value,"v1"),"v"):-1;
                return state.primitives.contains(slot)&&primitive(field(value,op==SET?"val":"v2"),state);
            }
            if(op==IF||op==WHILE) {
                if(!primitive(field(value,"v"),state)||!stableLoop(field(value,"val"),state.copy()))return false;
                return field(value,"else")==null||stableLoop(field(value,"else"),state.copy());
            }
            if(op==BREAK||op==CONTINUE)return true;
            return primitive(value,state);
        }
        private List<AcsObject> factory(AcsElement value) {
            if(opcode(value)!=CALL||!noContexts((AcsObject)value))return null;
            int id=number(value,"id");if(factories.containsKey(id))return factories.get(id);
            if(refusedFactories.contains(id))return null;refusedFactories.add(id);
            var function=functions.get(id);
            if(function==null||function.getAsInt("entry-kind")!=1||function.getAsInt("hidden-count")!=0
                    ||function.getAsInt("return-type")!=ADDRESS_VALUE||!(function.mmp.get("script") instanceof AcsArray body))return null;
            if(function.getAsAcsArray("param-types").acsa.stream().anyMatch(type->!scalar(((AcsIntegerElement)type).s)))return null;
            var list=body.acsa;
            if(list.size()!=4||opcode(list.get(0))!=DEFINE||opcode(list.get(1))!=CALL
                    ||opcode(list.get(2))!=OBJECT_BIND||opcode(list.get(3))!=RETURN_OBJECT)return null;
            var allocation=field(list.get(0),"val");int storage=number(list.get(0),"v");
            if(opcode(allocation)!=CALL||number(allocation,"id")!=ALLOCATE||!noContexts((AcsObject)allocation))return null;
            var sizes=((AcsObject)allocation).getAsAcsArray("param").acsa;
            if(sizes.size()!=1||!(sizes.get(0) instanceof AcsIntegerElement size)||size.s<1)return null;
            if(!variable(field(list.get(2),"v"),storage)||!noContexts((AcsObject)list.get(2))||!variable(field(list.get(3),"r"),storage))return null;
            var invoke=(AcsObject)list.get(1);var args=invoke.getAsAcsArray("param").acsa;
            if(!noContexts(invoke)||args.size()!=function.getAsInt("param-count")+1||!variable(args.get(0),storage))return null;
            for(int i=1;i<args.size();i++)if(!variable(args.get(i),i-1))return null;
            var entries=constructor(invoke.getAsInt("id"),new HashSet<>());
            if(entries!=null){factories.put(id,entries);return entries;}
            return null;
        }
        private List<AcsObject> constructor(int id,Set<Integer> visiting) {
            var function=functions.get(id);
            if(function==null||!visiting.add(id)||function.getAsInt("entry-kind")!=1||function.getAsInt("hidden-count")!=0
                    ||function.getAsInt("return-type")!=VOID_VALUE||function.getAsInt("param-count")==0)return null;
            var params=function.getAsAcsArray("param-types").acsa;
            if(((AcsIntegerElement)params.get(0)).s!=ADDRESS_VALUE)return null;
            for(int i=1;i<params.size();i++)if(!scalar(((AcsIntegerElement)params.get(i)).s))return null;
            var state=new State(function);var bound=new ArrayList<AcsObject>();
            if(!construction(function.mmp.get("script"),state,bound,visiting))return null;
            visiting.remove(id);return List.copyOf(bound);
        }
        private boolean construction(AcsElement value,State state,List<AcsObject> bound,Set<Integer> visiting) {
            int op=opcode(value);
            if(op==BLOCK) {
                for(var item:((AcsArray)value).acsa)if(!construction(item,state,bound,visiting))return false;
                return true;
            }
            if(address(value,0))return true;
            if(op==SET_METHODS&&variable(field(value,"v"),0)) {
                var entries=((AcsObject)value).getAsAcsArray("entries").acsa;
                if(entries.stream().anyMatch(entry->!noContexts((AcsObject)entry)))return false;
                bound.clear();for(var entry:entries)bound.add((AcsObject)entry);return true;
            }
            if(op==MOVE&&opcode(field(value,"v1"))==CALL&&number(field(value,"v1"),"id")==MEM_GET) {
                var args=((AcsObject)field(value,"v1")).getAsAcsArray("param").acsa;
                return args.size()==1&&opcode(args.get(0))==OBJECT_ADDRESS&&variable(field(args.get(0),"v"),0)
                        &&primitive(field(value,"v2"),state);
            }
            if(op==CALL&&noContexts((AcsObject)value)) {
                var args=((AcsObject)value).getAsAcsArray("param").acsa;
                if(args.isEmpty()||!variable(args.get(0),0)||!args.subList(1,args.size()).stream().allMatch(arg->primitive(arg,state)))return false;
                var inherited=constructor(number(value,"id"),visiting);
                if(inherited==null)return false;bound.clear();bound.addAll(inherited);return true;
            }
            return false;
        }
        AcsElement visit(AcsElement value,State state,boolean retainBlock) {
            if(value==null)return null;int op=opcode(value);
            if(op==BLOCK) {
                var block=(AcsArray)value;var locals=new HashSet<Integer>();
                for(int i=0;i<block.acsa.size();i++) {
                    var item=block.acsa.get(i);if(opcode(item)==DEFINE)locals.add(number(item,"v"));
                    block.acsa.set(i,visit(item,state,retainBlock));
                }
                for(int slot:locals){state.primitives.remove(slot);state.objects.remove(slot);}
                if(!retainBlock)state.clear();return value;
            }
            if(op==WHILE) {
                boolean stable=stableLoop(value,state.copy());
                if(!stable)state.clear();var local=state.copy();
                ((AcsObject)value).put("v",visit(field(value,"v"),local,stable));
                ((AcsObject)value).put("val",visit(field(value,"val"),local,stable));
                if(!stable)state.clear();return value;
            }
            if(op==IF||op==SELECT||op==AND||op==OR||op==CLEANUP) {
                if(op==CLEANUP) {
                    state.clear();
                    for(var child:ExecShape.children(value))if(code(child))
                        ExecOptimizer.replace(value,child,visit(child.value(),state.copy(),false));
                } else {
                    String condition=op==AND||op==OR?"v1":"v";
                    ((AcsObject)value).put(condition,visit(field(value,condition),state,false));
                    for(var child:ExecShape.children(value))if(code(child)&&child.value()!=field(value,condition))
                        ExecOptimizer.replace(value,child,visit(child.value(),state.copy(),false));
                }
                state.clear();return value;
            }
            if(op==DEFINE||op==SET||op==MOVE) {
                int slot=op==MOVE?(opcode(field(value,"v1"))==VARIABLE?number(field(value,"v1"),"v"):-1):number(value,"v");
                String rhs=op==MOVE?"v2":"val";AcsElement original=field(value,rhs);
                boolean scalarValue=primitive(original,state),oldPrimitive=state.primitives.contains(slot);
                var created=op==DEFINE&&slot>=0?factory(original):null;
                if(op==MOVE)((AcsObject)value).put("v1",visit(field(value,"v1"),state,false));
                if(original!=null)((AcsObject)value).put(rhs,visit(original,state,false));
                if(op!=DEFINE&&!oldPrimitive||!scalarValue&&created==null)state.clear();
                state.objects.remove(slot);state.primitives.remove(slot);
                if(op==DEFINE&&created!=null)state.objects.put(slot,created);
                else if(slot>=0&&scalarValue&&(op==DEFINE||oldPrimitive))state.primitives.add(slot);
                return value;
            }
            boolean scalarValue=primitive(value,state);
            var created=op==CALL?factory(value):null;
            for(var child:ExecShape.children(value))if(code(child))
                ExecOptimizer.replace(value,child,visit(child.value(),state,false));
            if(op==CALL_VIRTUAL) {
                var call=(AcsObject)value;var method=target(call,state);
                if(method!=null&&call.getAsAcsArray("param").acsa.stream().allMatch(ExecObjectOptimizer::pureArgument)) {
                    var direct=new AcsObject();direct.put("t",1);direct.put("id",method.getAsInt("id"));direct.put("param",call.mmp.get("param"));
                    if(!scalarValue)state.clear();return ExecO2.locate(direct,call,positions);
                }
                state.clear();return value;
            }
            if(op==CALL&&created==null&&!scalarValue||op==VALUE_COMPARE||op==OBJECT_BIND||op==OBJECT_DELETE||op==DROP||op==MOVE_OUT
                    ||op==RETURN_OBJECT||op==RETURN_TYPED||op==BUFFER_OP||op==BUFFER_NEW||op==CONTEXT_DEFAULT
                    ||op==SET_METHODS||op==CHECK_TYPE||op==CHECK_OBJECT||op==INCREMENT_I32_UNCHECKED)state.clear();
            return value;
        }
    }
}
