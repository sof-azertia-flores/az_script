#ifndef AZSCRIPT_JIT_NATIVE_H
#define AZSCRIPT_JIT_NATIVE_H

#include "abi.h"
#include "backend.h"
#include <memory>
#include <string>
#include <vector>

namespace azertian::jit {
// The loaded JIT module (libazscript-jit), or null with the reason. Loaded
// once per process from AZSCRIPT_JIT_MODULE, else from the runtime library's
// directory, else from the executable's; never unloaded.
const azjit_backend* native_backend(std::string& error);

// One function compiled by the module; released with it.
class NativeCode {
    const azjit_backend* backend_;
    void* code_=nullptr;
    azjit_entry entry_=nullptr;
    std::vector<azjit_op> ops_;
    std::vector<std::uint32_t> resume_ops_,resume_ids_,exit_slot_begin_,exit_slots_;
    std::vector<std::uint16_t> resume_tags_;
public:
    NativeCode(const azjit_backend& backend,const Function& function);
    ~NativeCode();
    NativeCode(const NativeCode&)=delete;
    NativeCode& operator=(const NativeCode&)=delete;
    bool compiled() const noexcept{return entry_;}
    std::string error;
    // Run from the resume point at pc with the same protocol as interpret().
    Outcome run(const Function& function,vm::ProgramCounter pc,FrameAccess& frame,Counters& counters,Activation& activation) const;
};
}
#endif
