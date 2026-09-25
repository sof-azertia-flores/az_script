package azertia.binary.complexBinary;

import azertia.binary.AbdValue;
import azertia.binary.util.Utils;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AcsFloat implements AcsElement{
    private float v;
    @Override
    public AbdValue toValue() {
        ByteBuffer bb= ByteBuffer.allocate(Float.BYTES);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putFloat(v);
        return new AbdValue(bb.array());
    }

    @Override
    public AbdValue typeValue() {
        return new AbdValue(Utils.i2b(0xce867));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(v);
    }

    public AcsFloat(float v){this.v=v;}
    public AcsFloat(AbdValue av){
        azertia.binary.AbdBasicType.requireSize(av, 4);
        ByteBuffer bb=ByteBuffer.wrap(av.getData());
        bb.order(ByteOrder.LITTLE_ENDIAN);
        v=bb.asFloatBuffer().get();
    }

    public float getV() {
        return v;
    }

    public void setV(float v) {
        this.v = v;
    }
}
