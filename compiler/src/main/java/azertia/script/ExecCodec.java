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

/** Strict fixed-record exec v5 encoder/decoder. Executable roots never use typed maps. */
public final class ExecCodec {
    public static final int VERSION=5, MAX_BYTES=64*1024*1024, MAX_SLOTS=1_048_576;
    public static final String MAGIC="AZSCRIPT";
    private ExecCodec() {}
    private record Layout(int globals,int parameters,int locals) {}
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
    private static String string(AcsElement value,String label) {
        if(!(value instanceof AcsStringElement string))throw invalid(label+" must be a string");
        return string.s;
    }
    private static boolean bool(AcsElement value,String label) {
        if(!(value instanceof AcsBooleanElement bool))throw invalid(label+" must be boolean");
        return bool.on;
    }
    private static void keys(AcsObject object,String required,String optional) {
        Set<String> allowed=new HashSet<>();
        for(String name:required.split(" "))if(!name.isEmpty()) {
            allowed.add(name);if(!object.mmp.containsKey(name)||object.mmp.get(name)==null)throw invalid("missing field "+name);
        }
        for(String name:optional.split(" "))if(!name.isEmpty())allowed.add(name);
        for(String name:object.mmp.keySet())if(!allowed.contains(name))throw invalid("unexpected field "+name);
    }
    private static AbdValue n(int value) {return AbdBasicType.int2Abd(value);}
    private static AbdValue b(boolean value) {return AbdBasicType.bol2Abd(value);}
    private static AbdValue s(String value) {return AbdBasicType.string2Abd(value);}
    private static AbdValue stack(List<AbdValue> values) {
        long bytes=0;
        for(AbdValue value:values) {
            bytes+=4L+value.getData().length;
            if(bytes>MAX_BYTES-4)throw invalid("executable exceeds 64 MiB");
        }
        AbdSimpleStack stack=new AbdSimpleStack();stack.values.addAll(values);return stack.toAbd();
    }
    private static AbdValue stack(AbdValue... values) {return stack(Arrays.asList(values));}
    private static int count(int value,String label) {
        if(value<0||value>MAX_SLOTS)throw invalid(label+" is outside 0.."+MAX_SLOTS);
        return value;
    }
    private static void type(int value,boolean parameter,boolean external) {
        if(value<0||value>6||value==5&&parameter||value==6&&(!parameter||external))
            throw invalid("invalid "+(parameter?"parameter":"return")+" type "+value);
    }
    private static void scriptId(int id) {
        if((id>>>16)==0xabd)throw invalid("invalid script function id "+id);
    }
    private static void externalId(int id) {
        if(id==0||id==1||id==0x0fff0000||(id>>>16)==0xabd)throw invalid("invalid external function id "+id);
    }
    private static void slot(int slot,Layout layout,boolean declaration) {
        if(slot<0) {
            if(declaration||-(long)slot>layout.globals())throw invalid("global variable slot outside its module: "+slot);
        } else if(slot>=layout.parameters()+layout.locals()||declaration&&slot<layout.parameters())
            throw invalid("local variable slot outside its declaration range: "+slot);
    }
    private static AbdValue types(AcsElement value,int expected,boolean external) {
        AcsArray types=array(value,"param-types");count(types.acsa.size(),"parameter type count");
        if(expected>=0&&types.acsa.size()!=expected)throw invalid("param-count and param-types disagree");
        List<AbdValue> encoded=new ArrayList<>();
        for(AcsElement item:types.acsa) {int type=integer(item,"parameter type");type(type,true,external);encoded.add(n(type));}
        return stack(encoded);
    }
    private static AbdValue extensions(AcsElement value) {
        AbdValue encoded=object(value,"ext").toValue();validateTyped(encoded,2,2);return encoded;
    }
    public static AbdValue encode(AcsObject program) {
        keys(program,"author version exec-version gvs ext extern-signatures f namespace-hint assume-hints","");
        String hint=string(program.mmp.get("namespace-hint"),"namespace-hint");
        if(!hint.isEmpty())hint(hint);
        if(integer(program,"exec-version")!=VERSION)throw invalid("unsupported exec version");
        int globals=count(integer(program,"gvs"),"global count");
        List<AbdValue> signatures=new ArrayList<>(),functions=new ArrayList<>();
        Set<Integer> externalIds=new HashSet<>(),functionIds=new HashSet<>(),namespaces=new HashSet<>(Set.of(0));
        for(AcsElement item:array(program.mmp.get("extern-signatures"),"extern-signatures").acsa) {
            AcsObject signature=object(item,"signature");keys(signature,"id return-type param-types","");
            int id=integer(signature,"id"),type=integer(signature,"return-type");externalId(id);type(type,false,true);
            if(!externalIds.add(id))throw invalid("duplicate external function id");
            signatures.add(stack(n(id),n(type),types(signature.mmp.get("param-types"),-1,true)));
        }
        for(AcsElement item:array(program.mmp.get("f"),"f").acsa) {
            AcsObject function=object(item,"function");keys(function,"id return-type param-count local-count param-types script","");
            int id=integer(function,"id"),type=integer(function,"return-type");scriptId(id);type(type,false,false);
            if(!functionIds.add(id))throw invalid("duplicate script function id");
            if(!hint.isEmpty()&&(id>>>16)!=0)throw invalid("hint module definitions must use namespace zero");
            namespaces.add(id>>>16);
            int params=count(integer(function,"param-count"),"parameter count"),locals=count(integer(function,"local-count"),"local count");
            if((long)params+locals>MAX_SLOTS)throw invalid("function frame exceeds slot limit");
            if((id==0||id==1)&&(type!=5||params!=0))throw invalid("invalid lifecycle function signature");
            functions.add(stack(n(id),n(type),n(params),n(locals),types(function.mmp.get("param-types"),params,false),
                    expression(function.mmp.get("script"),new Layout(globals,params,locals),4)));
        }
        List<AbdValue> assumptions=new ArrayList<>();Set<String> hints=new HashSet<>();Set<Integer> aliases=new HashSet<>();
        for(AcsElement item:array(program.mmp.get("assume-hints"),"assume-hints").acsa) {
            AcsObject assumption=object(item,"assumption");keys(assumption,"hint namespace","");
            String name=string(assumption.mmp.get("hint"),"hint");hint(name);int namespace=integer(assumption,"namespace");
            if(namespace<1||namespace>0xffff||namespace==0xabd||namespace==0xfff||namespaces.contains(namespace))
                throw invalid("invalid or fixed assumed namespace");
            if(!hints.add(name)||!aliases.add(namespace))throw invalid("duplicate hint or assumed namespace");
            assumptions.add(stack(s(name),n(namespace)));
        }
        if(!hint.isEmpty()&&!hints.contains(hint))throw invalid("hint module must assume itself");
        return stack(s(MAGIC),n(VERSION),n(integer(program,"version")),s(string(program.mmp.get("author"),"author")),
                n(globals),extensions(program.mmp.get("ext")),stack(signatures),stack(functions),s(hint),stack(assumptions));
    }
    private static void hint(String name) {
        if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw invalid("invalid hint name");
    }
    private static AbdValue expressions(AcsArray expressions,Layout layout,int level) {
        depth(level);List<AbdValue> values=new ArrayList<>();
        for(AcsElement expression:expressions.acsa)values.add(expression(expression,layout,level+1));
        return stack(values);
    }
    private static void scalar(AcsElement value) {
        if(value instanceof AcsIntegerElement||value instanceof AcsStringElement||value instanceof AcsBooleanElement)return;
        if(value instanceof AcsFloat number&&Float.isFinite(number.getV()))return;
        if(value instanceof AcsDouble number&&Double.isFinite(number.getV()))return;
        if(value instanceof AcsByteArray bytes&&Arrays.equals(bytes.getBytes(),new byte[]{(byte)0xff}))return;
        throw invalid("constant must be a supported finite scalar or void sentinel");
    }
    private static AbdValue expression(AcsElement value,Layout layout,int level) {
        depth(level);
        if(value instanceof AcsArray block)return stack(n(ExecOpcodes.BLOCK),expressions(block,layout,level+1));
        if(!(value instanceof AcsObject object)) {
            depth(level+1);scalar(value);AcsArray scalar=new AcsArray();scalar.acsa.add(value);
            return stack(n(ExecOpcodes.CONSTANT),scalar.toValue());
        }
        int kind=integer(object,"t");
        if(kind==1) {
            keys(object,"t id param","");
            return stack(n(ExecOpcodes.CALL),n(integer(object,"id")),expressions(array(object.mmp.get("param"),"param"),layout,level+1));
        }
        if(kind!=0)throw invalid("unknown expression kind "+kind);
        int op=integer(object,"c");ExecOpcodes.name(op);
        List<AbdValue> fields=new ArrayList<>();fields.add(n(op));
        switch(op) {
            case ExecOpcodes.VARIABLE -> {
                keys(object,"t c v","");int id=integer(object,"v");slot(id,layout,false);fields.add(n(id));
            }
            case ExecOpcodes.DEFINE -> {
                keys(object,"t c v","val declared-type");int id=integer(object,"v");slot(id,layout,true);
                int declared=object.mmp.containsKey("declared-type")?integer(object,"declared-type"):6;type(declared,true,false);
                fields.add(n(id));fields.add(n(declared));fields.add(b(object.mmp.containsKey("val")));
                if(object.mmp.containsKey("val"))fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.SET -> {
                keys(object,"t c v val","");int id=integer(object,"v");slot(id,layout,false);fields.add(n(id));
                fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.RETURN -> {
                keys(object,"t c","r");fields.add(b(object.mmp.containsKey("r")));
                if(object.mmp.containsKey("r"))fields.add(expression(object.mmp.get("r"),layout,level+1));
            }
            case ExecOpcodes.RETURN_OBJECT -> {
                keys(object,"t c r","");fields.add(expression(object.mmp.get("r"),layout,level+1));
            }
            case ExecOpcodes.OBJECT_ADDRESS -> {
                keys(object,"t c v offset","");int offset=integer(object,"offset");if(offset<0)throw invalid("negative field offset");
                fields.add(expression(object.mmp.get("v"),layout,level+1));fields.add(n(offset));
            }
            case ExecOpcodes.OBJECT_BIND -> {
                keys(object,"t c v manual","destructor");boolean hasDestructor=object.mmp.containsKey("destructor");
                fields.add(expression(object.mmp.get("v"),layout,level+1));fields.add(b(hasDestructor));
                if(hasDestructor) {int destructor=integer(object,"destructor");externalId(destructor);fields.add(n(destructor));}
                fields.add(b(bool(object.mmp.get("manual"),"manual")));
            }
            case ExecOpcodes.OBJECT_DELETE,ExecOpcodes.NOT,ExecOpcodes.NEGATE -> {
                keys(object,"t c v","");fields.add(expression(object.mmp.get("v"),layout,level+1));
            }
            case ExecOpcodes.IF -> {
                keys(object,"t c v val","else");fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expression(object.mmp.get("val"),layout,level+1));fields.add(b(object.mmp.containsKey("else")));
                if(object.mmp.containsKey("else"))fields.add(expression(object.mmp.get("else"),layout,level+1));
            }
            case ExecOpcodes.WHILE -> {
                keys(object,"t c v val","");fields.add(expression(object.mmp.get("v"),layout,level+1));
                fields.add(expression(object.mmp.get("val"),layout,level+1));
            }
            case ExecOpcodes.BREAK -> keys(object,"t c","");
            default -> {
                if(op!=ExecOpcodes.MOVE&&(op<ExecOpcodes.ADD||op>ExecOpcodes.OR))throw invalid("invalid control opcode "+op);
                keys(object,"t c v1 v2","");
                if(op==ExecOpcodes.MOVE) {
                    AcsObject target=object(object.mmp.get("v1"),"assignment target");int targetKind=integer(target,"t");
                    if(!(targetKind==0&&integer(target,"c")==ExecOpcodes.VARIABLE)
                            &&!(targetKind==1&&integer(target,"id")==0x0abd0006))throw invalid("invalid assignment target");
                }
                fields.add(expression(object.mmp.get("v1"),layout,level+1));fields.add(expression(object.mmp.get("v2"),layout,level+1));
            }
        }
        return stack(fields);
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
            case 0xce1066 -> AbdBasicType.requireSize(value,8);
            case 0x0d00 -> AbdBasicType.abd2bol(value);
            case 0xce2009 -> {if(value.getData().length==0)throw invalid("empty BigInteger payload");}
            case 0xce200a -> {}
            default -> throw invalid("unsupported extension type "+tag);
        }
    }
    public static AcsObject decode(AbdValue value) {
        if(value.getData().length>MAX_BYTES-4)throw invalid("executable exceeds 64 MiB");
        Record root=new Record(value,1);
        if(root.fields.isEmpty()||!Arrays.equals(root.fields.get(0).getData(),MAGIC.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("expected exec v5 AZSCRIPT header; legacy executable maps are unsupported");
        }
        root.string();int version=root.integer();if(version!=VERSION)throw invalid("unsupported exec version "+version);
        ExecProgram result=new ExecProgram();result.put("author","");result.put("version",root.integer());result.put("exec-version",version);
        result.put("author",root.string());result.put("ext",new AcsObject());result.put("extern-signatures",new AcsArray());
        result.put("gvs",root.integer());result.put("ext",decodeExtensions(root.next()));
        Record signatures=new Record(root.next(),2);AcsArray signatureView=new AcsArray();
        for(AbdValue item:signatures.fields) {
            Record signature=new Record(item,3);AcsObject view=new AcsObject();view.put("id",signature.integer());view.put("return-type",signature.integer());
            view.put("param-types",decodeTypes(signature.next(),4));signature.end();signatureView.acsa.add(view);
        }
        result.put("extern-signatures",signatureView);
        Record functions=new Record(root.next(),2);AcsArray functionView=new AcsArray();
        for(AbdValue item:functions.fields) {
            Record function=new Record(item,3);AcsObject view=new AcsObject();view.put("id",function.integer());view.put("return-type",function.integer());
            view.put("param-count",function.integer());view.put("local-count",function.integer());view.put("param-types",decodeTypes(function.next(),4));
            view.put("script",decodeExpression(function.next(),4));function.end();functionView.acsa.add(view);
        }
        result.put("namespace-hint",root.string());AcsArray assumptions=new AcsArray();
        Record assumptionRecords=new Record(root.next(),2);
        for(AbdValue item:assumptionRecords.fields) {
            Record assumption=new Record(item,3);AcsObject view=new AcsObject();view.put("hint",assumption.string());view.put("namespace",assumption.integer());
            assumption.end();assumptions.acsa.add(view);
        }
        result.put("assume-hints",assumptions);root.end();result.put("f",functionView);
        // One schema validator is shared by public inspection-map encoding and binary decoding.
        encode(result);return result;
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
            }
            case ExecOpcodes.VARIABLE -> view.put("v",record.integer());
            case ExecOpcodes.DEFINE -> {
                view.put("v",record.integer());int declared=record.integer();if(declared!=6)view.put("declared-type",declared);
                if(record.bool())view.put("val",decodeExpression(record.next(),level+1));
            }
            case ExecOpcodes.SET -> {view.put("v",record.integer());view.put("val",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.RETURN -> {if(record.bool())view.put("r",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.RETURN_OBJECT -> view.put("r",decodeExpression(record.next(),level+1));
            case ExecOpcodes.OBJECT_ADDRESS -> {view.put("v",decodeExpression(record.next(),level+1));view.put("offset",record.integer());}
            case ExecOpcodes.OBJECT_BIND -> {
                view.put("v",decodeExpression(record.next(),level+1));if(record.bool())view.put("destructor",record.integer());view.put("manual",record.bool());
            }
            case ExecOpcodes.OBJECT_DELETE,ExecOpcodes.NOT,ExecOpcodes.NEGATE -> view.put("v",decodeExpression(record.next(),level+1));
            case ExecOpcodes.IF -> {
                view.put("v",decodeExpression(record.next(),level+1));view.put("val",decodeExpression(record.next(),level+1));
                if(record.bool())view.put("else",decodeExpression(record.next(),level+1));
            }
            case ExecOpcodes.WHILE -> {view.put("v",decodeExpression(record.next(),level+1));view.put("val",decodeExpression(record.next(),level+1));}
            case ExecOpcodes.BREAK -> {}
            default -> {view.put("v1",decodeExpression(record.next(),level+1));view.put("v2",decodeExpression(record.next(),level+1));}
        }
        record.end();return view;
    }
}
