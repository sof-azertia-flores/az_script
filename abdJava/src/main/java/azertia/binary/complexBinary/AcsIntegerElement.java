package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class AcsIntegerElement implements AcsElement{
    public int s;
    public AcsIntegerElement(AbdValue av){
        s= AbdBasicType.abd2int(av);
    }
    public AcsIntegerElement(int x){s=x;}
    @Override
    public AbdValue toValue() {
        return AbdBasicType.int2Abd(s);
    }

    @Override
    public AbdValue typeValue() {
        return AbdBasicType.int2Abd(3);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(s);
    }

}
