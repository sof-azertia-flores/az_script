#ifndef AZSCRIPT_VM_LOWERING_H
#define AZSCRIPT_VM_LOWERING_H

#include "program.h"
#include "../core/exec.h"
#include <memory>

namespace azertian::vm {
// Lowering input: one validated function of the core exec model, with its
// function IDs already relocated. The adapter destroys it once a verified
// program is published.
struct SourceFunction {
    int function_id=0,return_type=VOID_VALUE,entry_kind=0;
    bool lifecycle=false;
    std::vector<int> param_types;
    std::uint32_t local_count=0,hidden_count=0,global_offset=0,global_count=0;
    std::optional<DebugInfo> debug;
    std::shared_ptr<const exec::expression> body;
};
struct SourceProgram {
    std::vector<SourceFunction> functions;
    // Include all resolved host/builtin targets. Script targets are generated
    // from functions, and duplicate target IDs are rejected.
    std::vector<CallTarget> external_targets;
};

// Lowering emits separate tick operations exactly at the old logical charges:
// node entry, successful loop iteration, and each method-table entry. Runtime
// helpers retain their additional per-capacity/per-object charges. Children
// inherit source positions, but parent operations restore the parent's site.
ExecutableProgram lower_program(const SourceProgram& source);
}
#endif
