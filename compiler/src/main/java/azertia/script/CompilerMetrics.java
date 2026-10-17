package azertia.script;

/**
 * Deterministic operation counters of one compilation, kept by its
 * {@link Diagnostics}. The scaling tests compare them for inputs of different
 * sizes: unlike timings, they do not depend on the machine or the JIT, so work
 * that grows faster than the input shows up exactly.
 */
final class CompilerMetrics {
    /** Scopes examined by variable definitions and lookups; each is one hash lookup. */
    long scopeProbes;
    /** Expression types computed, rather than taken from the memo of the running type query. */
    long typeEvaluations;
    /** AST nodes lowered to exec instructions. */
    long compiledNodes;
    /** Candidate IDs examined while assigning automatic function IDs and class factory IDs. */
    long idProbes;
    /**
     * Entries examined by per-item symbol-table checks outside scopes: field offsets,
     * abstract names, duplicate globals, classes and parameters, and the IDs an
     * automatic ID must avoid. A hash lookup examines one entry; a scan counts every
     * entry it passes, so a scan inside a per-item loop grows quadratically here.
     */
    long tableProbes;

    /** Every counted operation. */
    long total() { return scopeProbes + typeEvaluations + compiledNodes + idProbes + tableProbes; }

    @Override public String toString() {
        return "scope probes " + scopeProbes + ", type evaluations " + typeEvaluations
                + ", compiled nodes " + compiledNodes + ", ID probes " + idProbes + ", table probes " + tableProbes;
    }
}
