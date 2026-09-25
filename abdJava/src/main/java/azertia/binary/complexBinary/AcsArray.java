package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

public class AcsArray implements AcsElement {
    public AcsArray() {}
    public AcsArray(AbdValue value) throws ClassNotFoundException { acsa = aoa(value).acsa; }
    public List<AcsElement> acsa = new ArrayList<>();
    @Override public AbdValue toValue() {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            AbdSimpleStack stack = new AbdSimpleStack();
            for (AcsElement value : acsa) {
                if (value == null) throw new IllegalArgumentException("null ABD array element");
                stack.values.add(value.typeValue());
                stack.values.add(value.toValue());
            }
            return stack.toAbd();
        }
    }
    @Override public AbdValue typeValue() { return AbdBasicType.int2Abd(0xad); }
    @Override public JsonElement toJson() {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            JsonArray result = new JsonArray();
            for (AcsElement value : acsa) result.add(value.toJson());
            return result;
        }
    }
    public static AcsArray aoa(AbdValue value) throws ClassNotFoundException {
        try (AcsCodec.Guard ignored = AcsCodec.enter()) {
            List<AbdValue> entries = value.getAsAss().values;
            if (entries.size() % 2 != 0) throw new IllegalArgumentException("ABD array requires type/value pairs");
            AcsArray result = new AcsArray();
            for (int i = 0; i < entries.size(); i += 2)
                result.acsa.add(AcsCodec.decode(AbdBasicType.abd2int(entries.get(i)), entries.get(i + 1)));
            return result;
        }
    }
}
