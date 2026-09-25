package azertia;

import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.AcsIntegerElement;
import azertia.binary.complexBinary.AcsObject;
import azertia.binary.complexBinary.AcsStringElement;
import azertia.binary.structures.AsStructIO;
import azertia.binary.util.Utils;
import java.util.HexFormat;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {
    static void t(Object o){
        for(var x:o.getClass().getDeclaredFields()){
            System.out.println(x.getType());
        }
        System.out.println(o.getClass());
    }
    static void printBytes(byte[] bytes){
        for(var x:bytes){
            System.out.print((int)(x&0xff)+" ");
        }
        System.out.println();
    }
    public static void main(String[] args) throws IOException {
        AbdSimpleStack as = new AbdSimpleStack();
        byte[] bytes = {1,2,3,4,5};
        as.values.add(new AbdValue(bytes));
        printBytes(as.toAbd().toAbdFormat());
        AcsStringElement ase=new AcsStringElement("hello,world,你好");
        printBytes(ase.toValue().toAbdFormat());
        BigInteger bi=new BigInteger("127");
        System.out.println(HexFormat.of().formatHex( bi.toByteArray()));
        bi=new BigInteger("-128");
        System.out.println(HexFormat.of().formatHex( bi.toByteArray()));
        bi=new BigInteger("-100");
        System.out.println(HexFormat.of().formatHex( bi.toByteArray()));
        AcsObject ao=new AcsObject();
        ao.put("testAsmr",1.28);
        printBytes(ao.toValue().toAbdFormat());
        /*AcsObject ao=new AcsObject();
        ao.mmp.put("1st",new AcsStringElement("Hello,World"));
        ao.mmp.put("second",new AcsIntegerElement(1145141919));
        System.out.println(HexFormat.of().formatHex(ao.toValue().toAbdFormat()));
        Map<String,Object> mo=new HashMap<>();
        mo.put("1st","Hello,World");
        mo.put("second",1145141919);
        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        ObjectOutputStream oos=new ObjectOutputStream(baos);
        oos.writeObject(mo);
        oos.flush();
        System.out.println(HexFormat.of().formatHex(baos.toByteArray()));
        Map<String,String> skv=new HashMap<String,String>();
        System.out.println(Arrays.toString(skv.getClass().getGenericInterfaces()));
        System.out.println(skv.getClass().getGenericInterfaces());
        t(new TestTemplate());

        TestTemplate tt=new TestTemplate();
        tt.n=1145141919;
        tt.als=new ArrayList<>();
        tt.als.add("fuck");
        tt.i=0xad1453;
        tt.s="fuck you";
        tt.b=false;
        System.out.println(Integer.class.isAssignableFrom(java.lang.Number.class));
        byte[] b= AsStructIO.getAbdStructure(tt,false).toAbdFormat();
        System.out.println(HexFormat.of().formatHex(b));
        TestTemplate q= (TestTemplate) AsStructIO.readAbdStructure(AbdValue.fromAbd(b), TestTemplate.class,false);
        System.out.println(q.b+":"+q.s+":"+q.n+":"+q.i+":"+(q.sqr==null)+":"+q.als.get(0));
        ByteArrayOutputStream tbaos=new ByteArrayOutputStream();
        ObjectOutputStream oosa=new ObjectOutputStream(tbaos);
        oosa.writeObject(q);
        oosa.close();
        System.out.println(HexFormat.of().formatHex(tbaos.toByteArray()));

        AcsObject aoa=new AcsObject();
        aoa.put("obj",tt);
        System.out.println(HexFormat.of().formatHex(aoa.toValue().toAbdFormat()));*/
    }
}