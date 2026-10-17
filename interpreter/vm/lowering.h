#ifndef AZSCRIPT_VM_LOWERING_H
#define AZSCRIPT_VM_LOWERING_H

#include "program.h"
#include <memory>

namespace azertian::vm {
// Temporary neutral input built by the validated v10 loader. Function IDs are
// already relocated and contexts contain no reference to expression objects.
// The adapter destroys this input once a verified program is published.
struct SourceContext {
    std::optional<std::uint32_t> reference;
    int abi=VOID_VALUE,kind=0;
    std::uint32_t width=1;
    std::optional<int> factory_id,placement_id;
    std::vector<std::shared_ptr<const SourceContext>> contexts,placement_contexts;
    std::shared_ptr<const SourceContext> element;
};
struct SourceMethod {
    int function_id=0;
    std::vector<std::shared_ptr<const SourceContext>> contexts;
};
struct SourceExpression {
    // All wire opcodes 0..53 are accepted. There is no execute-tree escape.
    int opcode=0;
    std::uint32_t node=0,location=0;
    std::vector<std::shared_ptr<const SourceExpression>> children;
    Value constant;
    // slot/function ID/member offset/block size/buffer op/virtual slot/convert
    // target, according to opcode. return_type is used by call_virtual.
    int immediate=0,return_type=VOID_VALUE;
    // object-register destructor is optional; full int32 ID bits are retained.
    std::optional<int> destructor_id;
    bool manual=false,on_error=false;
    std::vector<std::shared_ptr<const SourceContext>> contexts;
    std::vector<SourceMethod> methods;
    std::vector<int> slot_types;
};
struct SourceFunction {
    int function_id=0,return_type=VOID_VALUE,entry_kind=0;
    bool lifecycle=false;
    std::vector<int> param_types;
    std::uint32_t local_count=0,hidden_count=0,global_offset=0,global_count=0;
    std::optional<DebugInfo> debug;
    std::shared_ptr<const SourceExpression> body;
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
