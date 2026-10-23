#ifndef AZSCRIPT_JIT_TIER_H
#define AZSCRIPT_JIT_TIER_H

#include "backend.h"
#include "native.h"
#include "config.h"
#include "../vm/program.h"
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace azertian::jit {
// The native size caps: MIR operations, and the dense resume table's cells
// (resume points times slots). Larger functions stay with the VM, recorded as "too large for
// native code".
struct NativeLimits {
    std::size_t operations,resume_cells;
};
// The caps, or for compile-time measurements AZSCRIPT_JIT_NATIVE_LIMITS
// ("<operations>,<resume cells>"); read when a tier is created.
NativeLimits native_limits();
// Resume points times slots, including shadows initialized absent.
std::size_t resume_cells(const Function& code) noexcept;
// Whether the LLVM backend compiles the function in bounded time.
bool fits_native(const Function& code,const NativeLimits& limits=native_limits()) noexcept;
// The dispatch loop's view of the JIT tier. Each frame keeps a bound
// (FrameRecord::end) that the loop already checks before every instruction;
// the tier lowers it to the next point where it may take over and is
// consulted only when a frame reaches it. Off, every bound is the function's
// end and back-edges are not reported, so the interpreter runs as before.
class Tier {
    struct Compiled {
        enum class State : std::uint8_t {untried,ineligible,ready} state=State::untried;
        FunctionAnalysis analysis;
        Function code;
        std::unique_ptr<NativeCode> native;           // with the llvm backend
        std::vector<vm::ProgramCounter> next_resume;  // per pc-begin: the first resume point after pc
    };
    const vm::ExecutableProgram& program_;
    Config config_;
    const azjit_backend* native_=nullptr;
    std::vector<vm::ProgramCounter> entry_bounds_;
    std::vector<bool> object_frames_;
    std::vector<Compiled> functions_;
    std::uint64_t random_;
    bool backedges_=false;
    Counters counters_;
    std::vector<std::uint64_t> heat_;  // events per untried function under the policy
    std::string unavailable_;          // why on runs without the tier
    std::size_t compiled_operations_=0;
    NativeLimits limits_;
    // Native compilation time, for the statistics side channel.
    std::uint64_t compile_ns_=0,compile_max_ns_=0,compile_max_operations_=0,compile_max_resume_cells_=0;
    Activation scratch_;              // shared: native runs never nest
    std::map<std::string,std::uint64_t> refusals_;
    vm::ProgramCounter random_bound(vm::ProgramCounter low,vm::ProgramCounter end) noexcept;
    Compiled& compiled(std::uint32_t function);
    vm::ProgramCounter after(const Compiled& entry,vm::ProgramCounter pc) const noexcept;
    bool policy() const noexcept;
    bool hot(std::uint32_t function) noexcept;
public:
    Tier(const vm::ExecutableProgram& program,const Config& config);
    ~Tier();
    Tier(const Tier&)=delete;
    Tier& operator=(const Tier&)=delete;
    // Which functions run as object frames, once the executor has classified
    // them; they stay interpreted.
    void classify(const std::vector<bool>& object_frames);
    const Config& config() const noexcept{return config_;}
    // The bound of a new frame of this function.
    vm::ProgramCounter entry_bound(std::uint32_t function) const noexcept{return entry_bounds_[function];}
    bool reports_backedges() const noexcept{return backedges_;}
    struct Reached {
        vm::ProgramCounter bound;
        bool ran=false;               // native code ran: the frame may have moved
    };
    // A frame of the function reached its bound at pc, before the function's
    // end. Native code may run from there; the result is the frame's next
    // bound, above its (possibly new) pc unless the tier resumes at once.
    Reached reached(std::uint32_t function,vm::ProgramCounter pc,FrameAccess& frame);
    // A loop of the function continues at pc: the frame's bound from now on.
    vm::ProgramCounter backedge(std::uint32_t function,vm::ProgramCounter pc,vm::ProgramCounter bound);
    const Counters& counters() const noexcept{return counters_;}
};
}
#endif
