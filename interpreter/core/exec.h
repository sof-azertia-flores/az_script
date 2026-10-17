#ifndef AZSCRIPT_CORE_EXEC_H
#define AZSCRIPT_CORE_EXEC_H
#include "debug.h"
#include "format.h"
#include "signature.h"
#include "types.h"
#include "value.h"
#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace azertian { class AbdMap; }

// A validated exec v10 module as both engines consume it. Nothing in the
// model points into the input bytes or into either engine's structures.
// Function IDs keep their wire namespaces; mounting and linking relocate them.
namespace azertian::exec {
struct context {
    // A hidden-context index of the enclosing function, else a type context.
    std::optional<std::uint32_t> reference;
    int abi=VOID_VALUE,kind=0;
    std::uint32_t width=1;
    std::optional<int> factory_id,placement_id;
    std::vector<std::shared_ptr<const context>> contexts,placement_contexts;
    std::shared_ptr<const context> element;
};
struct method {
    int function_id=0;
    std::vector<std::shared_ptr<const context>> contexts;
};
// One expression stack. Fields are used according to the opcode, as the
// exec format documents them.
struct expression {
    int opcode=0;
    // Pre-order number in the function body (the body is 0; contexts take
    // none) and the 1-based index of its debug location, or 0.
    std::uint32_t node=0,location=0;
    std::vector<std::shared_ptr<const expression>> children;
    Value constant;
    // slot, function ID, member offset, block size, buffer operation, virtual
    // slot or conversion target. return_type is used by call_virtual.
    int immediate=0,return_type=VOID_VALUE;
    std::optional<int> destructor_id;
    bool manual=false,on_error=false;
    std::vector<std::shared_ptr<const context>> contexts;
    std::vector<method> methods;
    std::vector<int> slot_types;
};
struct function {
    int function_id=0,return_type=VOID_VALUE,entry_kind=0;
    std::vector<int> param_types;
    std::uint32_t local_count=0,hidden_count=0;
    std::shared_ptr<const function_debug> debug;
    std::shared_ptr<const expression> body;
};
struct module {
    std::size_t global_offset=0,global_count=0;
    std::shared_ptr<AbdMap> meta;
    std::map<int,function_signature> extern_signatures;
    // Wire order; IDs are unique. 0 and 1 are the module's lifecycle hooks.
    std::vector<function> functions;
    std::string hint;
    // Assumed namespace -> hint.
    std::map<int,std::string> assumptions;
};
// Validates the whole file and returns its model. global_offset is where the
// module's globals start in the script; messages are the runtime's load errors.
module read_module(const unsigned char* bytes,std::size_t length,std::size_t global_offset);

enum class reference_kind { call,destructor,factory,placement,method };
struct reference {
    int function_id=0;
    reference_kind kind=reference_kind::call;
    std::uint32_t hidden_count=0,node=0,location=0;
};
// Link references of one body in discovery order: within an expression,
// nested contexts before the reference that owns them; mem_get(oa(...))
// member access is not a reference. Integer constants are never references.
std::vector<reference> references(const std::shared_ptr<const expression>& root);
// A copy with every reference replaced by resolver's result; root is unchanged
// even when resolver throws.
std::shared_ptr<const expression> relocate(const std::shared_ptr<const expression>& root,
                                           const std::function<int(const reference&)>& resolver);
}
#endif
