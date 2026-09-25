package azertia.binary.complexBinary;

import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.structures.AsStructIO;
import azertia.binary.util.Utils;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;

public class AcsWrappedObject<T> implements AcsElement {
    public T obj;
    @Override
    public AbdValue toValue() {
        AbdSimpleStack ass=new AbdSimpleStack();
        ass.values.add(new AbdValue((obj == null ? "" : obj.getClass().getName()).getBytes(StandardCharsets.UTF_8)));
        ass.values.add(AsStructIO.getAbdStructure(obj,true));
        return ass.toAbd();
    }

    @Override
    public AbdValue typeValue() {
        return new AbdValue(Utils.i2b(0xface));
    }

    @Override
    public JsonElement toJson() {
        return obj == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(obj.getClass().getName());
    }

    public T get(){
        return obj;
    }
    public AcsWrappedObject(){}
    public AcsWrappedObject(T obj){
        this.obj=obj;
    }
    @SuppressWarnings("unchecked")
    public AcsWrappedObject(AbdValue av) throws ClassNotFoundException {
        AbdSimpleStack ass=av.getAsAss();
        if (ass.values.size() != 2) throw new IllegalArgumentException("wrapped object requires class name and payload");
        if (ass.values.get(0).getData().length == 0) {
            if (ass.values.get(1).getData().length != 0) throw new IllegalArgumentException("invalid null wrapped object");
            obj = null;
            return;
        }
        Class<T> fn= (Class<T>) Class.forName(new String(ass.values.get(0).getData(), StandardCharsets.UTF_8), false, AcsWrappedObject.class.getClassLoader());
        obj= (T) AsStructIO.readAbdStructure(ass.values.get(1),fn,true);
    }
}
