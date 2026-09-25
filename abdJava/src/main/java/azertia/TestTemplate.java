package azertia;

import azertia.binary.structures.AsColum;
import azertia.binary.structures.AsList;
import azertia.binary.structures.AsStructure;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;

@AsStructure
public class TestTemplate  implements Serializable {
    @AsColum(order = 6)
    public String s;
    @AsColum()
    public Number n;
    @AsColum
    public int i;
    @AsColum
    public boolean b;
    @AsColum
    public String sqr;
    @AsColum
    @AsList(key = String.class)
    public ArrayList<String> als;
}
