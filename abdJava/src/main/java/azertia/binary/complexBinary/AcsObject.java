package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigInteger;
import java.util.LinkedHashMap;

import java.util.Map;

public class AcsObject implements AcsElement{
    public Map<String,AcsElement> mmp=new LinkedHashMap<>();
    @Override
    public AbdValue toValue() {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            AbdSimpleStack stack = new AbdSimpleStack();
            for (var entry : mmp.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null)
                    throw new IllegalArgumentException("null ABD object key/value");
                stack.values.add(AbdBasicType.string2Abd(entry.getKey()));
                stack.values.add(entry.getValue().typeValue());
                stack.values.add(entry.getValue().toValue());
            }
            return stack.toAbd();
        }
    }
    public AcsObject(){}
    public AcsObject(AbdValue value) throws ClassNotFoundException {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            var entries = value.getAsAss().values;
            if (entries.size() % 3 != 0) throw new IllegalArgumentException("ABD object requires name/type/value triples");
            for (int i = 0; i < entries.size(); i += 3) {
                String key = AbdBasicType.abd2str(entries.get(i));
                if (mmp.containsKey(key)) throw new IllegalArgumentException("duplicate ABD object key: " + key);
                mmp.put(key, AcsCodec.decode(AbdBasicType.abd2int(entries.get(i + 1)), entries.get(i + 2)));
            }
        }
    }
    public static AcsObject aot(AbdValue value) throws ClassNotFoundException {

        return new AcsObject(value);
    }
    public AcsObject getAsAcsObject(String key){
        return (AcsObject) mmp.get(key);
    }
    public AcsArray getAsAcsArray(String key){
        return (AcsArray) mmp.get(key);
    }
    public String getAsString(String key){
        return ((AcsStringElement)mmp.get(key)).s;
    }
    public double getAsDouble(String key){
        return ((AcsDouble)mmp.get(key)).getV();
    }
    public float getAsFloat(String key){
        return ((AcsFloat)mmp.get(key)).getV();
    }
    public int getAsInt(String key){
        return ((AcsIntegerElement)mmp.get(key)).s;
    }
    public long getAsLong(String key){
        return ((AcsBigInteger)mmp.get(key)).value.longValueExact();

    }
    public Object getAsObject(String key){
        return ((AcsWrappedObject)mmp.get(key)).obj;
    }
    public BigInteger getAsBigInteger(String key){
        return ((AcsBigInteger)mmp.get(key)).value;
    }
    public byte[] getAsBytes(String key){
        AcsElement value = mmp.get(key);
        if (value instanceof AcsByteArray) return ((AcsByteArray)value).getBytes();
        return ((AcsBigInteger)value).toValue().getData();
    }
    public boolean getAsBool(String key){
        return ((AcsBooleanElement)mmp.get(key)).on;
    }
    public void put(String key,String value){
        mmp.put(key,new AcsStringElement(value));
    }
    public void put(String key,int value){
        mmp.put(key,new AcsIntegerElement(value));
    }
    public void put(String key,boolean value){
        mmp.put(key,new AcsBooleanElement(value));
    }
    public void put(String key,BigInteger value){
        mmp.put(key,new AcsBigInteger(value));
    }
    public void put(String key,long value){
        mmp.put(key,new AcsBigInteger(value));
    }
    public void put(String key,byte[] value){
        mmp.put(key,new AcsByteArray(value));
    }
    public void put(String key,Object obj){
        mmp.put(key,new AcsWrappedObject<>(obj));
    }
    public void put(String key,double obj){
        mmp.put(key,new AcsDouble(obj));
    }public void put(String key,float obj){
        mmp.put(key,new AcsFloat(obj));
    }
    public void put(String key,AcsElement obj){
        mmp.put(key,obj);
    }
    @Override
    public AbdValue typeValue() {
        return AbdBasicType.int2Abd(2);
    }

    @Override
    public JsonElement toJson() {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            JsonObject result = new JsonObject();
            for (var entry : mmp.entrySet()) result.add(entry.getKey(), entry.getValue().toJson());
            return result;
        }
    }
}
