#ifndef AZSCRIPT_JIT_TIER_H
#define AZSCRIPT_JIT_TIER_H

#include "config.h"
#include "../vm/program.h"
#include <vector>

namespace azertian::jit {
// The dispatch loop's view of the JIT tier. Each frame keeps a bound
// (FrameRecord::end) that the loop already checks before every instruction;
// the tier lowers it to the next point where it may take over and is
// consulted only when a frame reaches it. Off, every bound is the function's
// end and back-edges are not reported, so the interpreter runs as before.
class Tier {
    const vm::ExecutableProgram& program_;
    Config config_;
    std::vector<vm::ProgramCounter> entry_bounds_;
    std::uint64_t random_;
    bool backedges_=false;
    vm::ProgramCounter random_bound(vm::ProgramCounter low,vm::ProgramCounter end) noexcept;
public:
    Tier(const vm::ExecutableProgram& program,const Config& config);
    const Config& config() const noexcept{return config_;}
    // The bound of a new frame of this function.
    vm::ProgramCounter entry_bound(std::uint32_t function) const noexcept{return entry_bounds_[function];}
    bool reports_backedges() const noexcept{return backedges_;}
    // A frame of the function reached its bound at pc, before the function's
    // end: the frame's next bound, above pc.
    vm::ProgramCounter reached(std::uint32_t function,vm::ProgramCounter pc);
    // A loop of the function continues at pc: the frame's bound from now on.
    vm::ProgramCounter backedge(std::uint32_t function,vm::ProgramCounter pc,vm::ProgramCounter bound);
};
}
#endif
