package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class AcsStringElement implements AcsElement{
    public String s;
    public AcsStringElement(AbdValue av){
        s=AbdBasicType.abd2str(av);
    }
    public AcsStringElement(String x){s=x;}
    @Override
    public AbdValue toValue() {
        return AbdBasicType.string2Abd(s);
    }

    @Override
    public AbdValue typeValue() {
        return AbdBasicType.int2Abd(1);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(s);
    }

}
