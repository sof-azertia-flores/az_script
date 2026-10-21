#ifndef AZSCRIPT_JIT_BACKEND_H
#define AZSCRIPT_JIT_BACKEND_H

#include "frame_access.h"
#include "mir.h"
#include "shadow.h"
#include <cstdint>
#include <vector>

namespace azertian::jit {
struct Counters {
    std::uint64_t entries=0,rejects=0,steps=0,redos=0,yields=0,operations=0;
    // Values outside the tags the analysis allowed: an unsound analysis.
    std::uint64_t violations=0;
};
// Native state of one activation: a shadow per slot, the control flags and
// the step count.
struct Activation {
    std::vector<Shadow> slots;
    std::uint8_t flags=0;
    std::uint64_t steps=0;
};
// Load the shadows the analysis expects at a resume point from VM memory.
// False (nothing changed) when memory disagrees, e.g. a host gave a register
// a value without a shadow: the VM then continues.
bool fill(const Function& function,vm::ProgramCounter pc,const FrameAccess& frame,Activation& activation,Counters& counters);
// Write the exit's live register shadows and every present local shadow,
// the flags, the step count, the static depths and the pc back into VM memory.
void write_back(const Function& function,std::uint32_t site,FrameAccess& frame,const Activation& activation) noexcept;

struct Outcome {
    bool entered=false;
    ExitKind kind=ExitKind::redo;
    vm::ProgramCounter pc=0;
};
// The portable backend: interpret the function's MIR from the resume point at
// pc until an exit, with the same entry and exit protocol as native code.
// activation is scratch storage, reused: native runs never nest.
Outcome interpret(const Function& function,vm::ProgramCounter pc,FrameAccess& frame,Counters& counters,Activation& activation);
}
#endif
