package ix.idg.controllers;

import java.util.Map;

public class DescriptorVector extends Vector {
    public DescriptorVector (Class kind, String field) {
        super (kind, field);
    }

    public void add (Map<String, Number> data) {
        Number nv = data.get(field);
        if (nv != null) {
            if (min == null || min.doubleValue() > nv.doubleValue())
                min = nv;
            if (max == null || max.doubleValue() < nv.doubleValue())
                max = nv;
            values.add(nv);
        }
    }
}
