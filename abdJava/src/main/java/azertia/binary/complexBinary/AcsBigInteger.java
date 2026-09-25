package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import azertia.binary.structures.AsStructure;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigInteger;

@AsStructure
public class AcsBigInteger implements AcsElement{
    public BigInteger value;
    private byte[] originalBytes;
    private BigInteger originalValue;
    public AcsBigInteger(long v){
        value= BigInteger.valueOf(v);
    }
    public AcsBigInteger(BigInteger b){
        value=b;
    }
    public AcsBigInteger(AbdValue av){
        byte[] bytes = av.getData();
        if (bytes.length == 0) throw new IllegalArgumentException("empty BigInteger payload");
        value = new BigInteger(bytes);
        originalValue = value;
        originalBytes = bytes;
    }

    @Override
    public AbdValue toValue() {
        return new AbdValue(value.equals(originalValue) ? originalBytes : value.toByteArray());
    }

    @Override
    public AbdValue typeValue() {
        return AbdBasicType.int2Abd(0xce2009);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

}
