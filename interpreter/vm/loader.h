#ifndef AZSCRIPT_VM_LOADER_H
#define AZSCRIPT_VM_LOADER_H

#include "program.h"
#include <map>

namespace azertian { class script; }

namespace azertian::vm {
struct ModuleEntrypoints {
    std::uint32_t onload=no_index,pre_destroy=no_index;
};
struct CompiledScript {
    ExecutableProgram program;
    std::map<int,std::uint32_t> public_functions,functions;
    // Same order as script::module_manifest(). Lifecycle IDs are local to a
    // module, so they cannot be indexed by the public function-ID map.
    std::vector<ModuleEntrypoints> modules;
};

// Called only after the ordinary v10 loader and link validation have accepted
// the modules. Temporary source nodes are discarded after lowering. This does
// not run onload, mutate the script, or retain pointers into expression trees.
CompiledScript compile_script(const azertian::script& script);
}
#endif
