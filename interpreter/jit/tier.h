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
    std::map<std::string,std::uint64_t> refusals_;
    vm::ProgramCounter random_bound(vm::ProgramCounter low,vm::ProgramCounter end) noexcept;
    Compiled& compiled(std::uint32_t function);
    vm::ProgramCounter after(const Compiled& entry,vm::ProgramCounter pc) const noexcept;
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
