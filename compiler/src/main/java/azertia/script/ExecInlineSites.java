package azertia.script;

import azertia.binary.complexBinary.AcsElement;
import java.util.*;

/**
 * The inlined call sites of one compilation, for exec v11 extended debug records.
 * A position of an inlined node carries its site as a fourth element; the site
 * names the callee and the sequence that replaced the call, whose own position
 * carries the enclosing site.
 */
final class ExecInlineSites {
    private final List<Integer> callees = new ArrayList<>();
    private final IdentityHashMap<AcsElement,Integer> callSites = new IdentityHashMap<>();

    /** A new site for an inlined call of callee; numbers start at 1. */
    int add(int callee) { callees.add(callee); return callees.size(); }
    void callSite(int site, AcsElement replacement) { callSites.put(replacement, site); }
    /** The site whose call the node replaced, if any. */
    Integer siteOf(AcsElement node) { return callSites.get(node); }
    int callee(int site) { return callees.get(site - 1); }

    /** A source position {file, line, column} placed inside site; 0 leaves it outside any. */
    static int[] tagged(int[] position, int site) {
        if (position == null) return null;
        if (site == 0 && position.length == 3) return position;
        int[] result = Arrays.copyOf(position, 4); result[3] = site; return result;
    }
    static int site(int[] position) { return position.length > 3 ? position[3] : 0; }
}
