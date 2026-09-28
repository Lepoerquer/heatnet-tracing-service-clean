package ru.heatnet.calc.reference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.CalcException;

/** Табл. 4.2: габариты по DN. */
public final class GabaritTable {

    private final Map<Integer, GabaritSpec> byDn;

    public GabaritTable(List<GabaritSpec> specs) {
        Map<Integer, GabaritSpec> map = new LinkedHashMap<>();
        for (GabaritSpec s : specs) {
            if (map.put(s.getDn(), s) != null) {
                throw new CalcException("Табл. 4.2: DN" + s.getDn() + " указан дважды");
            }
        }
        this.byDn = Collections.unmodifiableMap(map);
    }

    public GabaritSpec spec(int dn) {
        GabaritSpec s = byDn.get(dn);
        if (s == null) {
            throw new CalcException("DN" + dn + " отсутствует в табл. 4.2");
        }
        return s;
    }

    public Map<Integer, GabaritSpec> all() {
        return byDn;
    }
}
