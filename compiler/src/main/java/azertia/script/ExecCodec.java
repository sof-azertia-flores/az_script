package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Strict fixed-record exec v10 encoder/decoder. Executable roots never use typed maps.
 * Optional per-function debug records locate body nodes by their pre-order number;
 * they never affect execution.
 */
public final class ExecCodec {
    public static final int VERSION=10, MAX_BYTES=64*1024*1024, MAX_SLOTS=1_048_576;
    public static final String MAGIC="AZSCRIPT";
    private ExecCodec() {}
    private record Layout(int globals,int parameters,int locals,int hidden,Numbering numbering) {}
    /**
     * Numbers the expression stacks of one function body in pre-order and wire field
     * order: the body is node 0, and contexts take no number. With positions, it also
     * collects the debug locations of the positioned nodes, leaving out a node whose
     * position equals that of its nearest positioned ancestor.
     */
    private static final class Numbering {
        int next;
        final Map<AcsElement,int[]> positions;
        final AcsArray located=new AcsArray();
        int[] inherited;
        Numbering(Map<AcsElement,int[]> positions) {this.positions=positions;}
    }
    private static void depth(int depth) {
        if(depth>128)throw new IllegalArgumentException("Exec ABD nesting exceeds 128 levels");
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid exec: "+message);
    }
    private static AcsObject object(AcsElement value,String label) {
        if(!(value instanceof AcsObject object))throw invalid(label+" must be an object");
        return object;
    }
    private static AcsArray array(AcsElement value,String label) {
        if(!(value instanceof AcsArray array))throw invalid(label+" must be an array");
        return array;
    }
    private static int integer(AcsElement value,String label) {
        if(!(value instanceof AcsIntegerElement integer))throw invalid(label+" must be int32");
        return integer.s;
    }
    private static int integer(AcsObject object,String key) {return integer(object.mmp.get(key),key);}
    private static int optionalInteger(AcsObject object,String key,int fallback) {
        return object.mmp.containsKey(key)?integer(object,key):fallback;
    }
    private static int entryKind(AcsObject value) {
        int kind=optionalInteger(value,"entry-kind",0);
        if(kind!=0&&kind!=1)throw invalid("entry-kind must be ordinary (0) or internal (1)");
        return kind;
    }
    private static String string(AcsElement value,String label) {
        if(!(value instanceof AcsStringElement string))throw invalid(label+" must be a string");
        return string.s;
    }
    private static boolean bool(AcsElement value,String label) {
        if(!(value instanceof AcsBooleanElement bool))throw invalid(label+" must be boolean");
        return bool.on;
    }
    /** The required fields of a record, in the order they are checked, and every field it may have. */
    private record KeySpec(String[] required,Set<String> allowed) {
        static KeySpec of(String required,String optional) {
            List<String> names=new ArrayList<>();Set<String> allowed=new HashSet<>();
            for(String name:required.split(" "))if(!name.isEmpty()){names.add(name);allowed.add(name);}
            for(String name:optional.split(" "))if(!name.isEmpty())allowed.add(name);
            return new KeySpec(names.toArray(String[]::new),Collections.unmodifiableSet(allowed));
        }
    }
    private static final KeySpec REFERENCE=KeySpec.of("ref",""),
            FIXED_CONTEXT=KeySpec.of("abi kind","width factory contexts placement placement-contexts element"),
            PROGRAM=KeySpec.of("author version exec-version gvs ext extern-signatures f namespace-hint assume-hints debug-files",""),
            SIGNATURE=KeySpec.of("id return-type param-types","hidden-count entry-kind"),
            FUNCTION=KeySpec.of("id return-type param-count local-count param-types script","hidden-count entry-kind debug"),
            DEBUG=KeySpec.of("name file line column locations",""),
            ASSUMPTION=KeySpec.of("hint namespace",""),
            CALL=KeySpec.of("t id param","contexts"),
            OPERAND=KeySpec.of("t c v",""),
            DEFINE=KeySpec.of("t c v","val declared-type"),
            OPERAND_VALUE=KeySpec.of("t c v val",""),
            RETURN=KeySpec.of("t c","r"),
            RETURN_OBJECT=KeySpec.of("t c r",""),
            OBJECT_ADDRESS=KeySpec.of("t c v offset",""),
            OBJECT_BIND=KeySpec.of("t c v manual","destructor contexts"),
            IF=KeySpec.of("t c v val","else"),
            CLEANUP=KeySpec.of("t c v val on-error",""),
            JUMP=KeySpec.of("t c",""),
            NEW_BLOCK=KeySpec.of("t c size",""),
            CONTEXT_OPERATION=KeySpec.of("t c context",""),
            TYPED_OPERAND=KeySpec.of("t c v context",""),
            BUFFER_OPERATION=KeySpec.of("t c op v args",""),
            VALUE_COMPARE=KeySpec.of("t c context v1 v2",""),
            CONVERSION=KeySpec.of("t c type v",""),
            BINARY=KeySpec.of("t c v1 v2","");
    private static void keys(AcsObject object,KeySpec keys) {
        for(String name:keys.required())if(!object.mmp.containsKey(name)||object.mmp.get(name)==null)throw invalid("missing field "+name);
        for(String name:object.mmp.keySet())if(!keys.allowed().contains(name))throw invalid("unexpected field "+name);
    }
    private static AbdValue n(int value) {return AbdBasicType.int2Abd(value);}
    private static AbdValue b(boolean value) {return AbdBasicType.bol2Abd(value);}
    private static AbdValue s(String value) {return AbdBasicType.string2Abd(value);}
    private static AbdValue stack(List<AbdValue> values) {
        long bytes=0;
        for(AbdValue value:values) {
            bytes+=4L+value.size();
            if(bytes>MAX_BYTES-4)throw invalid("executable exceeds 64 MiB");
        }
        AbdSimpleStack stack=new AbdSimpleStack();stack.values=values;return stack.toAbd();
    }
    private static AbdValue stack(AbdValue... values) {return stack(Arrays.asList(values));}
    private static int count(int value,String label) {
        if(value<0||value>MAX_SLOTS)throw invalid(label+" is outside 0.."+MAX_SLOTS);
        return value;
    }
    private static void type(int value,boolean parameter,boolean external) {
        if(value<0||value>8||value==5&&parameter)
            throw invalid("invalid "+(parameter?"parameter":"return")+" type "+value);
    }
    private static void conversionType(int type) {
        if(type<0||type>3&&type!=7)throw invalid("invalid conversion target type "+type);
    }
    private static void scriptId(int id) {
        if(SourceRules.runtimeId(id))throw invalid("invalid script function id "+Diagnostic.hexId(id));
    }
    private static void externalId(int id) {
        if(SourceRules.reservedExternalId(id))throw invalid("invalid external function id "+Diagnostic.hexId(id));
    }
    private static void slot(int slot,Layout layout,boolean declaration) {
        if(slot<0) {
            if(declaration||-(long)slot>layout.globals())throw invalid("global variable slot outside its module: "+slot);
        } else if(slot>=layout.parameters()+layout.locals()||declaration&&slot<layout.parameters())
            throw invalid("local variable slot outside its declaration range: "+slot);
    }
    private static AbdValue types(AcsElement value,int expected,boolean external,int hidden) {
        AcsArray types=array(value,"param-types");count(types.acsa.size(),"parameter type count");
        if(expected>=0&&types.acsa.size()!=expected)throw invalid("param-count and param-types disagree");
        List<AbdValue> encoded=new ArrayList<>();
        for(AcsElement item:types.acsa) {
            int type=integer(item,"parameter type");type(type,true,external);
            if(external&&type==6&&hidden==0)throw invalid("external any parameter requires generic contexts");
            encoded.add(n(type));
        }
        return stack(encoded);
    }
    private static AbdValue contexts(AcsElement value,Layout layout,int level) {
        depth(level);
        if(value==null)return stack();
        AcsArray specs=array(value,"contexts");count(specs.acsa.size(),"context count");
        List<AbdValue> result=new ArrayList<>();
        for(AcsElement spec:specs.acsa)result.add(context(spec,layout,level+1));
        return stack(result);
    }
    private static AbdValue context(AcsElement value,Layout layout,int level) {
        depth(level);AcsObject spec=object(value,"context");
        if(spec.mmp.containsKey("ref")) {
            keys(spec,REFERENCE);int reference=integer(spec,"ref");
            if(reference<0||reference>=layout.hidden())throw invalid("context reference outside function hidden-count");
            return stack(n(0),n(reference));
        }
        keys(spec,FIXED_CONTEXT);int abi=integer(spec,"abi"),kind=integer(spec,"kind");
        if(!(kind==0&&abi>=0&&abi<=5||kind==1&&abi==7||kind==2&&abi==7||(kind==3||kind==4)&&abi==8))
            throw invalid("invalid fixed context ABI/kind");
        int width=optionalInteger(spec,"width",1);
        if(width<1||width>MAX_SLOTS||kind!=3&&width!=1)throw invalid("invalid context slot width");
        boolean hasFactory=spec.mmp.containsKey("factory");
        if(hasFactory&&kind!=3)throw invalid("only object contexts can have a default factory");
        if(!hasFactory&&spec.mmp.containsKey("contexts")&&!array(spec.mmp.get("contexts"),"contexts").acsa.isEmpty())
            throw invalid("context without a factory cannot have child contexts");
        boolean hasPlacement=spec.mmp.containsKey("placement"),hasElement=spec.mmp.containsKey("element");
        if(hasPlacement&&kind!=3)throw invalid("only object contexts can have a placement constructor");
        if(!hasPlacement&&spec.mmp.containsKey("placement-contexts")&&!array(spec.mmp.get("placement-contexts"),"placement-contexts").acsa.isEmpty())
            throw invalid("context without placement cannot have placement contexts");
        if(hasElement!=(kind==4))throw invalid("only buffer contexts require an element context");
        List<AbdValue> fields=new ArrayList<>(List.of(n(1),n(abi),n(kind),n(width),b(hasFactory)));
        if(hasFactory) {int factory=integer(spec,"factory");externalId(factory);fields.add(n(factory));}
        fields.add(contexts(spec.mmp.get("contexts"),layout,level+1));fields.add(b(hasPlacement));
        if(hasPlacement) {int placement=integer(spec,"placement");externalId(placement);fields.add(n(placement));}
        fields.add(contexts(spec.mmp.get("placement-contexts"),layout,level+1));fields.add(b(hasElement));
        if(hasElement) {
            rejectVoidContext(spec.mmp.get("element"));fields.add(context(spec.mmp.get("element"),layout,level+1));
        }
        return stack(fields);
    }
    private static void rejectVoidContext(AcsElement value) {
        AcsObject spec=object(value,"context");
        if(!spec.mmp.containsKey("ref")&&integer(spec,"abi")==5)throw invalid("void context is not a value type");
    }
    private static AbdValue extensions(AcsElement value) {
        AbdValue encoded=object(value,"ext").toValue();validateTyped(encoded,2,2);return encoded;
    }
    public static AbdValue encode(AcsObject program) {
        keys(program,PROGRAM);
        String hint=string(program.mmp.get("namespace-hint"),"namespace-hint");
        if(!hint.isEmpty())hint(hint);
        if(integer(program,"exec-version")!=VERSION)throw invalid("unsupported exec version");
        int globals=count(integer(program,"gvs"),"global count");
        List<String> files=debugFiles(program.mmp.get("debug-files"));
        List<AbdValue> signatures=new ArrayList<>(),functions=new ArrayList<>();
        Set<Integer> externalIds=new HashSet<>(),functionIds=new HashSet<>(),namespaces=new HashSet<>(Set.of(0));
        for(AcsElement item:array(program.mmp.get("extern-signatures"),"extern-signatures").acsa) {
            AcsObject signature=object(item,"signature");keys(signature,SIGNATURE);
            int id=integer(signature,"id"),type=integer(signature,"return-type");externalId(id);type(type,false,true);
            if(!externalIds.add(id))throw invalid("duplicate external function id");
            int hidden=count(optionalInteger(signature,"hidden-count",0),"hidden context count"),entry=entryKind(signature);
            if(type==6&&hidden==0)throw invalid("any return type requires generic contexts");
            signatures.add(stack(n(id),n(type),types(signature.mmp.get("param-types"),-1,true,hidden),n(hidden),n(entry)));
        }
        for(AcsElement item:array(program.mmp.get("f"),"f").acsa) {
            AcsObject function=object(item,"function");keys(function,FUNCTION);
            int id=integer(function,"id"),type=integer(function,"return-type");scriptId(id);type(type,false,false);
            if(!functionIds.add(id))throw invalid("duplicate script function id");
            if(!hint.isEmpty()&&(id>>>16)!=0)throw invalid("hint module definitions must use namespace zero");
            namespaces.add(id>>>16);
            int params=count(integer(function,"param-count"),"parameter count"),locals=count(integer(function,"local-count"),"local count");
            int hidden=count(optionalInteger(function,"hidden-count",0),"hidden context count"),entry=entryKind(function);
            if(type==6&&hidden==0)throw invalid("any return type requires generic contexts");
            if((long)params+locals>MAX_SLOTS)throw invalid("function frame exceeds slot limit");
            if((id==0||id==1)&&(type!=5||params!=0||hidden!=0))throw invalid("invalid lifecycle function signature");
            Numbering numbering=new Numbering(null);
            AbdValue parameterTypes=types(function.mmp.get("param-types"),params,false,hidden);
            AbdValue body=expression(function.mmp.get("script"),new Layout(globals,params,locals,hidden,numbering),4);
            List<AbdValue> record=new ArrayList<>(List.of(n(id),n(type),n(params),n(locals),parameterTypes,body,n(hidden),n(entry)));
            AcsElement debug=function.mmp.get("debug");record.add(b(debug!=null));
            if(debug!=null)record.add(functionDebug(debug,files,numbering.next));
            functions.add(stack(record));
        }
        List<AbdValue> assumptions=new ArrayList<>();Set<String> hints=new HashSet<>();Set<Integer> aliases=new HashSet<>();
        for(AcsElement item:array(program.mmp.get("assume-hints"),"assume-hints").acsa) {
            AcsObject assumption=object(item,"assumption");keys(assumption,ASSUMPTION);
            String name=string(assumption.mmp.get("hint"),"hint");hint(name);int namespace=integer(assumption,"namespace");
            if(!SourceRules.assumableNamespace(namespace)||namespaces.contains(namespace))
                throw invalid("invalid or fixed assumed namespace");
            if(!hints.add(name)||!aliases.add(namespace))throw invalid("duplicate hint or assumed namespace");
            assumptions.add(stack(s(name),n(namespace)));
        }
        if(!hint.isEmpty()&&!hints.contains(hint))throw invalid("hint module must assume itself");
        List<AbdValue> fileNames=new ArrayList<>();for(String file:files)fileNames.add(s(file));
        return stack(s(MAGIC),n(VERSION),n(integer(program,"version")),s(string(program.mmp.get("author"),"author")),
                n(globals),extensions(program.mmp.get("ext")),stack(signatures),stack(functions),s(hint),stack(assumptions),stack(fileNames));
    }
    /** The root debug file table: distinct valid display names, at most {@link #MAX_SLOTS}. */
    private static List<String> debugFiles(AcsElement value) {
        AcsArray array=array(value,"debug-files");count(array.acsa.size(),"debug file count");
        List<String> files=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(AcsElement item:array.acsa) {
            String file=debugText(item,"debug file name");
            if(!seen.add(file))throw invalid("duplicate debug file name");
            files.add(file);
        }
        return files;
    }
    private static String debugText(AcsElement value,String label) {
        String text=string(value,label),problem=SourceRules.debugTextProblem(text);
        if(problem!=null)throw invalid(label+" "+problem);
        return text;
    }
    private static int debugFile(int file,List<String> files) {
        if(file<0||file>=files.size())throw invalid("debug file index outside debug-files");
        return file;
    }
    /**
     * A function debug record {@code [name, file, line, column, locations]}. The header
     * position is unknown when line and column are both 0. Locations are a flat int
     * stack of {@code (node, file, line, column)} quads with strictly increasing nodes
     * below the body's node count; the inspection view writes each quad as "n:f:l:c".
     */
    private static AbdValue functionDebug(AcsElement value,List<String> files,int nodes) {
        AcsObject debug=object(value,"debug");keys(debug,DEBUG);
        String name=debugText(debug.mmp.get("name"),"debug name");
        int file=debugFile(integer(debug,"file"),files),line=integer(debug,"line"),column=integer(debug,"column");
        if(line<0||column<0||(line==0)!=(column==0))throw invalid("debug header position must be both zero or both positive");
        AcsArray locations=array(debug.mmp.get("locations"),"debug locations");count(locations.acsa.size(),"debug location count");
        List<AbdValue> quads=new ArrayList<>();int previous=-1;
        for(AcsElement item:locations.acsa) {
            int[] location=location(string(item,"debug location"));
            if(location[0]<=previous||location[0]>=nodes)throw invalid("debug location nodes must increase and refer to body nodes");
            debugFile(location[1],files);
            if(location[2]<1||location[3]<1)throw invalid("debug location line and column must be positive");
            previous=location[0];for(int part:location)quads.add(n(part));
        }
        return stack(s(name),n(file),n(line),n(column),stack(quads));
    }
    /** Parses "node:file:line:column" in canonical decimal. */
    private static int[] location(String text) {
        String[] parts=text.split(":",-1);
        if(parts.length!=4)throw invalid("debug location must be node:file:line:column");
        int[] result=new int[4];
        for(int i=0;i<4;i++) {
            String part=parts[i];
            boolean canonical=!part.isEmpty()&&part.length()<=10&&(part.equals("0")||part.charAt(0)!='0');
            for(int j=0;canonical&&j<part.length();j++)canonical=part.charAt(j)>='0'&&part.charAt(j)<='9';
            long number=canonical?Long.parseLong(part):-1;
            if(number<0||number>Integer.MAX_VALUE)throw invalid("debug location must be node:file:line:column");
            result[i]=(int)number;
        }
        return result;
    }
    private static String location(int node,int file,int line,int column) {return node+":"+file+":"+line+":"+column;}
    /**
     * The debug locations of a compiled function view, as "node:file:line:column"
     * strings: {@code positions} maps compiled nodes to {@code {file, line, column}}.
     */
    static AcsArray locations(AcsObject function,int globals,Map<AcsElement,int[]> positions) {
        Numbering numbering=new Numbering(positions);
        expression(function.mmp.get("script"),new Layout(globals,integer(function,"param-count"),integer(function,"local-count"),
                optionalInteger(function,"hidden-count",0),numbering),4);
        return numbering.located;
    }
    private static void hint(String name) {
        if(!SourceRules.isHint(name))throw invalid("invalid hint name");
    }
    private static AbdValue expressions(AcsArray expressions,Layout layout,int level) {
        depth(level);List<AbdValue> values=new ArrayList<>();
        for(AcsElement expression:expressions.acsa)values.add(expression(expression,layout,level+1));
        return stack(values);
    }
    private static void scalar(AcsElement value) {
        if(value instanceof AcsIntegerElement||value instanceof AcsStringElement||value instanceof AcsBooleanElement||value instanceof AcsAddress)return;
        if(value instanceof AcsFloat number&&Float.isFinite(number.getV()))return;
        if(value instanceof AcsDouble number&&Double.isFinite(number.getV()))return;
        if(value instanceof AcsByteArray bytes&&Arrays.equals(bytes.getBytes(),new byte[]{(byte)0xff}))return;
        throw invalid("constant must be a supported finite scalar or void sentinel");
    }
    private static boolean binaryOpcode(int op) {
        return op==ExecOpcodes.MOVE||op>=ExecOpcodes.ADD&&op<=ExecOpcodes.OR
                ||op>=ExecOpcodes.BIT_AND&&op<=ExecOpcodes.SHIFT_UNSIGNED;
    }
    private static AbdValue expression(AcsElement value,Layout layout,int level) {
        Numbering numbering=layout.numbering();int node=numbering.next++;
        if(numbering.positions==null)return expressionBody(value,layout,level);
        int[] outer=numbering.inherited,at=numbering.positions.get(value);
        if(at!=null) {
            if(!Arrays.equals(at,outer))numbering.located.acsa.add(new AcsStringElement(location(node,at[0],at[1],at[2])));
            numbering.inherited=at;
        }
        try {return expressionBody(value,layout,level);}
        finally {numbering.inherited=outer;}
    }
    private static AbdValue expressionBody(AcsElement value,Layout layout,int level) {
        depth(level);
        if(value instanceof AcsArray block)return stack(n(ExecOpcodes.BLOCK),expressions(block,layout,level+1));
        if(!(value instanceof AcsObject object)) {
            depth(level+1);scalar(value);AcsArray scalar=new AcsArray();scalar.acsa.add(value);
            return stack(n(ExecOpcodes.CONSTANT),scalar.toValue());
        }
        int kind=integer(object,"t");
        if(kind==1) {
            keys(object,CALL);
            return stack(n(ExecOpcodes.CALL),n(integer(object,"id")),expressions(array(object.mmp.get("param"),"param"),layout,level+1),
                    contexts(object.mmp.get("contexts"),layout,level+1));
        }
        if(kind!=0)throw invalid("unknown expression kind "+kind);
        int op=integer(object,"c");ExecOpcodes.name(op);
        List<AbdValue> fields=new ArrayList<>();fields.add(n(op));
        switch(op) {
            case ExecOpcodes.VARIABLE -> {
                keys(object,OPERAND);int id=integer(object,"v");slot(id,layout,false);fields.add(n(id));
            }
            case ExecOpcodes.DEFINE -> {
                keys(object,DEFINE);int id=integer(object,"v");slot(id,layout,true);
                int declared=object.mmp.containsKey("declared-type")?integer(object,"declared-type"):6;type(declared,true,false);
                fields.add(n(id));fields.add(n(declared));fields.add(b(object.mmp.containsKey("val")));
                if(object.mmp.containsKey("val"))fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.SET -> {
                keys(object,OPERAND_VALUE);int id=integer(object,"v");slot(id,layout,false);fields.add(n(id));
                fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.RETURN -> {
                keys(object,RETURN);fields.add(b(object.mmp.containsKey("r")));
                if(object.mmp.containsKey("r"))fields.add(expression(object.mmp.get("r"),layout,level+1));
            }
            case ExecOpcodes.RETURN_OBJECT -> {
                keys(object,RETURN_OBJECT);fields.add(expression(object.mmp.get("r"),layout,level+1));
            }
            case ExecOpcodes.OBJECT_ADDRESS -> {
                keys(object,OBJECT_ADDRESS);int offset=integer(object,"offset");if(offset<0)throw invalid("negative field offset");
                fields.add(expression(object.mmp.get("v"),layout,level+1));fields.add(n(offset));
            }
            case ExecOpcodes.OBJECT_BIND -> {
                keys(object,OBJECT_BIND);boolean hasDestructor=object.mmp.containsKey("destructor");
                fields.add(expression(object.mmp.get("v"),layout,level+1));fields.add(b(hasDestructor));
                if(hasDestructor) {int destructor=integer(object,"destructor");externalId(destructor);fields.add(n(destructor));}
                fields.add(b(bool(object.mmp.get("manual"),"manual")));
                if(!hasDestructor&&object.mmp.containsKey("contexts")&&!array(object.mmp.get("contexts"),"contexts").acsa.isEmpty())
                    throw invalid("object without a destructor cannot bind destructor contexts");
                fields.add(contexts(object.mmp.get("contexts"),layout,level+1));
            }
            case ExecOpcodes.OBJECT_DELETE,ExecOpcodes.NOT,ExecOpcodes.NEGATE -> {
                keys(object,OPERAND);fields.add(expression(object.mmp.get("v"),layout,level+1));
            }
            case ExecOpcodes.IF -> {
                keys(object,IF);fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expression(object.mmp.get("val"),layout,level+1));fields.add(b(object.mmp.containsKey("else")));
                if(object.mmp.containsKey("else"))fields.add(expression(object.mmp.get("else"),layout,level+1));
            }
            case ExecOpcodes.WHILE -> {
                keys(object,OPERAND_VALUE);fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.CLEANUP -> {
                keys(object,CLEANUP);fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expression(object.mmp.get("val"),layout,level+1));fields.add(b(bool(object.mmp.get("on-error"),"on-error")));
            }
            case ExecOpcodes.BREAK,ExecOpcodes.CONTINUE -> keys(object,JUMP);
            case ExecOpcodes.NEW_BLOCK -> {
                keys(object,NEW_BLOCK);int size=integer(object,"size");
                if(size<1||size>MAX_SLOTS)throw invalid("object slot count is outside 1.."+MAX_SLOTS);
                fields.add(n(size));
            }
            case ExecOpcodes.BLOCK_ADDRESS -> {keys(object,OPERAND);fields.add(expression(object.mmp.get("v"),layout,level+1));}
            case ExecOpcodes.MOVE_OUT -> {
                keys(object,OPERAND);int id=integer(object,"v");
                if(id<0)throw invalid("object move requires a local slot");
                slot(id,layout,false);fields.add(n(id));
            }
            case ExecOpcodes.DROP -> {
                keys(object,OPERAND);storageTarget(object(object.mmp.get("v"),"drop target"),"drop target");
                fields.add(expression(object.mmp.get("v"),layout,level+1));
            }
            case ExecOpcodes.CONTEXT_ABI,ExecOpcodes.CONTEXT_DEFAULT,ExecOpcodes.BUFFER_NEW -> {
                if(op==ExecOpcodes.BUFFER_NEW)rejectVoidContext(object.mmp.get("context"));
                keys(object,CONTEXT_OPERATION);fields.add(context(object.mmp.get("context"),layout,level+1));
            }
            case ExecOpcodes.RETURN_TYPED,ExecOpcodes.CHECK_TYPE -> {
                keys(object,TYPED_OPERAND);fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(context(object.mmp.get("context"),layout,level+1));
            }
            case ExecOpcodes.BUFFER_OP -> {
                keys(object,BUFFER_OPERATION);int operation=integer(object,"op");
                if(operation<0||operation>6)throw invalid("invalid buffer operation");
                AcsArray args=array(object.mmp.get("args"),"buffer arguments");
                int expected=operation<2?0:operation==4?2:1;
                if(args.acsa.size()!=expected)throw invalid("invalid buffer argument count");
                fields.add(n(operation));fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expressions(args,layout,level+1));
            }
            case ExecOpcodes.VALUE_COMPARE -> {
                rejectVoidContext(object.mmp.get("context"));
                keys(object,VALUE_COMPARE);fields.add(context(object.mmp.get("context"),layout,level+1));
                fields.add(expression(object.mmp.get("v1"),layout,level+1));fields.add(expression(object.mmp.get("v2"),layout,level+1));
            }
            case ExecOpcodes.CONVERT -> {
                keys(object,CONVERSION);int type=integer(object,"type");conversionType(type);
                fields.add(n(type));fields.add(expression(object.mmp.get("v"),layout,level+1));
            }
            default -> {
                if(!binaryOpcode(op))throw invalid("invalid control opcode "+op);
                keys(object,BINARY);
                if(op==ExecOpcodes.MOVE)storageTarget(object(object.mmp.get("v1"),"assignment target"),"assignment target");
                fields.add(expression(object.mmp.get("v1"),layout,level+1));fields.add(expression(object.mmp.get("v2"),layout,level+1));
            }
        }
        return stack(fields);
    }
    /** Assignment and drop targets name a variable slot or the built-in mem_get. */
    private static void storageTarget(AcsObject target,String label) {
        int targetKind=integer(target,"t");
        if(!(targetKind==0&&integer(target,"c")==ExecOpcodes.VARIABLE)
                &&!(targetKind==1&&integer(target,"id")==0x0abd0006))throw invalid("invalid "+label);
    }
    private static final class Record {
        final List<AbdValue> fields;int position;
        Record(AbdValue value,int level) {depth(level);fields=value.getAsAss().values;}
        AbdValue next() {if(position==fields.size())throw invalid("missing record field");return fields.get(position++);}
        int integer() {return AbdBasicType.abd2int(next());}
        boolean bool() {return AbdBasicType.abd2bol(next());}
        String string() {return utf8(next());}
        void end() {if(position!=fields.size())throw invalid("extra record fields");}
    }
    private static String utf8(AbdValue value) {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.getData())).toString();}
        catch(CharacterCodingException error) {throw invalid("malformed UTF-8 string");}
    }
    private static AcsArray decodeTypes(AbdValue value,int level) {
        Record record=new Record(value,level);count(record.fields.size(),"parameter type count");AcsArray result=new AcsArray();
        while(record.position<record.fields.size())result.acsa.add(new AcsIntegerElement(record.integer()));
        return result;
    }
    private static AcsArray decodeContexts(AbdValue value,int level) {
        Record record=new Record(value,level);count(record.fields.size(),"context count");AcsArray result=new AcsArray();
        for(AbdValue item:record.fields)result.acsa.add(decodeContext(item,level+1));
        return result;
    }
    private static AcsObject decodeContext(AbdValue value,int level) {
        Record record=new Record(value,level);int variant=record.integer();AcsObject result=new AcsObject();
        if(variant==0)result.put("ref",record.integer());
        else if(variant==1) {
            result.put("abi",record.integer());result.put("kind",record.integer());result.put("width",record.integer());
            if(record.bool())result.put("factory",record.integer());
            result.put("contexts",decodeContexts(record.next(),level+1));
            if(record.bool())result.put("placement",record.integer());
            result.put("placement-contexts",decodeContexts(record.next(),level+1));
            if(record.bool())result.put("element",decodeContext(record.next(),level+1));
        } else throw invalid("unknown context specification kind "+variant);
        record.end();return result;
    }
    private static AcsObject decodeExtensions(AbdValue value) {
        validateTyped(value,2,2);
        try {return new AcsObject(value);}
        catch(ClassNotFoundException error) {throw invalid("unsupported extension type");}
    }
    private static void validateTyped(AbdValue value,int tag,int level) {
        switch(tag) {
            case 2,0xad -> {
                Record record=new Record(value,level);Set<String> keys=new HashSet<>();
                while(record.position<record.fields.size()) {
                    if(tag==2&&!keys.add(record.string()))throw invalid("duplicate extension key");
                    int childType=record.integer();validateTyped(record.next(),childType,level+1);
                }
            }
            case 1 -> utf8(value);
            case 3,0xce867 -> AbdBasicType.requireSize(value,4);
            case 0xce1066,AcsAddress.TYPE -> AbdBasicType.requireSize(value,8);
            case 0x0d00 -> AbdBasicType.abd2bol(value);
            case 0xce2009 -> {if(value.size()==0)throw invalid("empty BigInteger payload");}
            case 0xce200a -> {}
            default -> throw invalid("unsupported extension type "+tag);
        }
    }
    public static AcsObject decode(AbdValue value) {
        if(value.size()>MAX_BYTES-4)throw invalid("executable exceeds 64 MiB");
        Record root=new Record(value,1);
        if(root.fields.isEmpty()||!Arrays.equals(root.fields.get(0).getData(),MAGIC.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("expected exec v"+VERSION+" AZSCRIPT header; legacy executable maps are unsupported");
        }
        root.string();int version=root.integer();
        if(version!=VERSION)throw invalid("unsupported exec version "+version+"; expected exec v"+VERSION);
        ExecProgram result=new ExecProgram();result.put("author","");result.put("version",root.integer());result.put("exec-version",version);
        result.put("author",root.string());result.put("ext",new AcsObject());result.put("extern-signatures",new AcsArray());
        result.put("gvs",root.integer());result.put("ext",decodeExtensions(root.next()));
        Record signatures=new Record(root.next(),2);AcsArray signatureView=new AcsArray();
        for(AbdValue item:signatures.fields) {
            Record signature=new Record(item,3);AcsObject view=new AcsObject();view.put("id",signature.integer());view.put("return-type",signature.integer());
            view.put("param-types",decodeTypes(signature.next(),4));view.put("hidden-count",signature.integer());
            view.put("entry-kind",signature.integer());signature.end();signatureView.acsa.add(view);
        }
        result.put("extern-signatures",signatureView);
        Record functions=new Record(root.next(),2);AcsArray functionView=new AcsArray();
        for(AbdValue item:functions.fields) {
            Record function=new Record(item,3);AcsObject view=new AcsObject();view.put("id",function.integer());view.put("return-type",function.integer());
            view.put("param-count",function.integer());view.put("local-count",function.integer());view.put("param-types",decodeTypes(function.next(),4));
            view.put("script",decodeExpression(function.next(),4));view.put("hidden-count",function.integer());
            view.put("entry-kind",function.integer());
            if(function.bool())view.put("debug",decodeDebug(function.next(),4));
            function.end();functionView.acsa.add(view);
        }
        result.put("namespace-hint",root.string());AcsArray assumptions=new AcsArray();
        Record assumptionRecords=new Record(root.next(),2);
        for(AbdValue item:assumptionRecords.fields) {
            Record assumption=new Record(item,3);AcsObject view=new AcsObject();view.put("hint",assumption.string());view.put("namespace",assumption.integer());
            assumption.end();assumptions.acsa.add(view);
        }
        result.put("assume-hints",assumptions);AcsArray files=new AcsArray();
        for(AbdValue item:new Record(root.next(),2).fields)files.acsa.add(new AcsStringElement(utf8(item)));
        result.put("debug-files",files);root.end();result.put("f",functionView);
        // One schema validator is shared by public inspection-map encoding and binary decoding.
        encode(result);return result;
    }
    private static AcsObject decodeDebug(AbdValue value,int level) {
        Record record=new Record(value,level);AcsObject view=new AcsObject();
        view.put("name",record.string());view.put("file",record.integer());view.put("line",record.integer());view.put("column",record.integer());
        Record quads=new Record(record.next(),level+1);record.end();
        if(quads.fields.size()%4!=0)throw invalid("debug locations must be node:file:line:column quads");
        AcsArray locations=new AcsArray();
        while(quads.position<quads.fields.size())
            locations.acsa.add(new AcsStringElement(location(quads.integer(),quads.integer(),quads.integer(),quads.integer())));
        view.put("locations",locations);return view;
    }
    public static AcsObject decode(byte[] frame) {
        if(frame.length>MAX_BYTES)throw invalid("executable exceeds 64 MiB");return decode(AbdValue.fromAbd(frame));
    }
    private static AcsArray decodeExpressions(AbdValue value,int level) {
        Record record=new Record(value,level);AcsArray array=new AcsArray();
        for(AbdValue item:record.fields)array.acsa.add(decodeExpression(item,level+1));return array;
    }
    private static AcsElement decodeScalar(AbdValue value,int level) {
        Record record=new Record(value,level);int type=record.integer();AbdValue payload=record.next();record.end();
        AcsElement result=switch(type) {
            case 1 -> new AcsStringElement(utf8(payload));
            case 3 -> new AcsIntegerElement(payload);
            case 0x0d00 -> new AcsBooleanElement(payload);
            case 0xce867 -> new AcsFloat(payload);
            case 0xce1066 -> new AcsDouble(payload);
            case 0xce200a -> new AcsByteArray(payload);
            case AcsAddress.TYPE -> new AcsAddress(payload);
            default -> throw invalid("unsupported constant type "+type);
        };
        scalar(result);return result;
    }
    private static AcsElement decodeExpression(AbdValue value,int level) {
        Record record=new Record(value,level);int op=record.integer();ExecOpcodes.name(op);AcsObject view=new AcsObject();
        view.put("t",0);view.put("c",op);
        switch(op) {
            case ExecOpcodes.CONSTANT -> {AcsElement scalar=decodeScalar(record.next(),level+1);record.end();return scalar;}
            case ExecOpcodes.BLOCK -> {AcsArray block=decodeExpressions(record.next(),level+1);record.end();return block;}
            case ExecOpcodes.CALL -> {
                view=new AcsObject();view.put("t",1);view.put("id",record.integer());view.put("param",decodeExpressions(record.next(),level+1));
                view.put("contexts",decodeContexts(record.next(),level+1));
            }
            case ExecOpcodes.VARIABLE -> view.put("v",record.integer());
            case ExecOpcodes.DEFINE -> {
                view.put("v",record.integer());int declared=record.integer();if(declared!=6)view.put("declared-type",declared);
                if(record.bool())view.put("val",decodeExpression(record.next(),level+1));
            }
            case ExecOpcodes.SET -> {view.put("v",record.integer());view.put("val",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.CLEANUP -> {
                view.put("v",decodeExpression(record.next(),level+1));view.put("val",decodeExpression(record.next(),level+1));
                view.put("on-error",record.bool());
            }
            case ExecOpcodes.RETURN -> {if(record.bool())view.put("r",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.RETURN_OBJECT -> view.put("r",decodeExpression(record.next(),level+1));
            case ExecOpcodes.OBJECT_ADDRESS -> {view.put("v",decodeExpression(record.next(),level+1));view.put("offset",record.integer());}
            case ExecOpcodes.OBJECT_BIND -> {
                view.put("v",decodeExpression(record.next(),level+1));if(record.bool())view.put("destructor",record.integer());view.put("manual",record.bool());
                view.put("contexts",decodeContexts(record.next(),level+1));
            }
            case ExecOpcodes.OBJECT_DELETE,ExecOpcodes.NOT,ExecOpcodes.NEGATE -> view.put("v",decodeExpression(record.next(),level+1));
            case ExecOpcodes.IF -> {
                view.put("v",decodeExpression(record.next(),level+1));view.put("val",decodeExpression(record.next(),level+1));
                if(record.bool())view.put("else",decodeExpression(record.next(),level+1));
            }
            case ExecOpcodes.WHILE -> {view.put("v",decodeExpression(record.next(),level+1));view.put("val",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.BREAK,ExecOpcodes.CONTINUE -> {}
            case ExecOpcodes.NEW_BLOCK -> view.put("size",record.integer());
            case ExecOpcodes.MOVE_OUT -> view.put("v",record.integer());
            case ExecOpcodes.BLOCK_ADDRESS,ExecOpcodes.DROP -> view.put("v",decodeExpression(record.next(),level+1));
            case ExecOpcodes.CONTEXT_ABI,ExecOpcodes.CONTEXT_DEFAULT,ExecOpcodes.BUFFER_NEW -> view.put("context",decodeContext(record.next(),level+1));
            case ExecOpcodes.RETURN_TYPED,ExecOpcodes.CHECK_TYPE -> {
                view.put("v",decodeExpression(record.next(),level+1));view.put("context",decodeContext(record.next(),level+1));
            }
            case ExecOpcodes.BUFFER_OP -> {
                view.put("op",record.integer());view.put("v",decodeExpression(record.next(),level+1));
                view.put("args",decodeExpressions(record.next(),level+1));
            }
            case ExecOpcodes.VALUE_COMPARE -> {
                view.put("context",decodeContext(record.next(),level+1));view.put("v1",decodeExpression(record.next(),level+1));
                view.put("v2",decodeExpression(record.next(),level+1));
            }
            case ExecOpcodes.CONVERT -> {
                int type=record.integer();conversionType(type);view.put("type",type);
                view.put("v",decodeExpression(record.next(),level+1));
            }
            default -> {
                if(!binaryOpcode(op))throw invalid("invalid control opcode "+op);
                view.put("v1",decodeExpression(record.next(),level+1));view.put("v2",decodeExpression(record.next(),level+1));
            }
        }
        record.end();return view;
    }
}
