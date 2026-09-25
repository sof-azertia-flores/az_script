package azertia.binary.complexBinary;

import azertia.binary.AbdValue;
import azertia.binary.structures.AsStructure;
import com.google.gson.JsonElement;

@AsStructure
public interface AcsElement {
    AbdValue toValue();
    AbdValue typeValue();
    JsonElement toJson();
}
