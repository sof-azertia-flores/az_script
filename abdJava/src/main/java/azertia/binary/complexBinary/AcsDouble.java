package azertia.binary.complexBinary;

import azertia.binary.AbdValue;
import azertia.binary.util.Utils;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;

public class AcsDouble implements AcsElement{
    private double v;
    @Override
    public AbdValue toValue() {
        ByteBuffer bb= ByteBuffer.allocate(Double.BYTES);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putDouble(v);
        return new AbdValue(bb.array());
    }

    @Override
    public AbdValue typeValue() {
        return new AbdValue(Utils.i2b(0xce1066));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(v);
    }

    public AcsDouble(double v){this.v=v;}

    public AcsDouble(AbdValue av){
        azertia.binary.AbdBasicType.requireSize(av, 8);
        ByteBuffer bb=ByteBuffer.wrap(av.getData());
        bb.order(ByteOrder.LITTLE_ENDIAN);
        v=bb.asDoubleBuffer().get();
    }

    public double getV() {
        return v;
    }

    public void setV(double v) {
        this.v = v;
    }
}
