package azertia.script;

import java.util.Map;
import java.util.LinkedHashMap;

/** Stable opcode numbers shared by the exec inspection view and compact ABD wire format. */
public final class ExecOpcodes {
    public static final int CONSTANT=0, BLOCK=1, CALL=2, VARIABLE=3, DEFINE=4, SET=5,
            MOVE=6, RETURN=7, RETURN_OBJECT=8, OBJECT_ADDRESS=9, OBJECT_BIND=10,
            OBJECT_DELETE=11, ADD=12, SUBTRACT=13, MULTIPLY=14, DIVIDE=15, MODULO=16,
            GREATER=17, LESS=18, EQUAL=19, NOT_EQUAL=20, GREATER_EQUAL=21, LESS_EQUAL=22,
            AND=23, OR=24, NOT=25, NEGATE=26, IF=27, WHILE=28, BREAK=29, CONTINUE=30, CLEANUP=31;
    private static final String[] NAMES={"constant","block","call","v","vd","vs","m","r","ro",
            "oa","ob","od","add","minus","multiply","divide","mod","gt","lt","eq","ne","ge","le",
            "and","or","not","neg","if","wi","brk","cont","cleanup"};
    private static final Map<String,Integer> CODES=new LinkedHashMap<>();
    static { for(int i=0;i<NAMES.length;i++) CODES.put(NAMES[i],i); }
    private ExecOpcodes() {}
    public static int code(String name) {
        Integer code=CODES.get(name);
        if(code==null) throw new IllegalArgumentException("Unknown exec operation: "+name);
        return code;
    }
    public static String name(int code) {
        if(code<0||code>=NAMES.length) throw new IllegalArgumentException("Unknown exec opcode: "+code);
        return NAMES[code];
    }
}
