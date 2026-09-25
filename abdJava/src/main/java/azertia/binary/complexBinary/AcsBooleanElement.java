package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import azertia.binary.structures.AsColum;
import azertia.binary.structures.AsStructure;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

@AsStructure
public class AcsBooleanElement implements AcsElement{
    @AsColum
    public boolean on;
    @Override
    public AbdValue toValue() {
        return AbdBasicType.bol2Abd(on);
    }
    public AcsBooleanElement(boolean b){
        on=b;
    }
    public AcsBooleanElement(AbdValue av){
        on=AbdBasicType.abd2bol(av);
    }
    @Override
    public AbdValue typeValue() {
        return AbdBasicType.int2Abd(0x0d00);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(on);
    }

}
