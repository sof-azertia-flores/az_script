#include "tier.h"
#include <stdexcept>

namespace azertian::jit {
Tier::Tier(const vm::ExecutableProgram& program,const Config& config)
    :program_(program),config_(config),random_(config.seed) {
    entry_bounds_.reserve(program.functions.size());
    // A probing frame consults the tier at its first instruction.
    for(const auto& function:program.functions)
        entry_bounds_.push_back(config.mode==Mode::probe?function.begin:function.end);
    backedges_=config.mode==Mode::probe;
}
// splitmix64: a fixed sequence for a seed, so a probe run is reproducible.
vm::ProgramCounter Tier::random_bound(vm::ProgramCounter low,vm::ProgramCounter end) noexcept {
    auto z=(random_+=0x9e3779b97f4a7c15ULL);
    z=(z^(z>>30))*0xbf58476d1ce4e5b9ULL;z=(z^(z>>27))*0x94d049bb133111ebULL;z^=z>>31;
    return low+static_cast<vm::ProgramCounter>(z%(end-low+1));
}
vm::ProgramCounter Tier::reached(std::uint32_t function,vm::ProgramCounter pc) {
    const auto end=program_.functions[function].end;
    if(pc>=end)throw std::logic_error("JIT tier consulted at the end of a function");
    if(config_.mode==Mode::off)return end;
    return random_bound(pc+1,end);
}
vm::ProgramCounter Tier::backedge(std::uint32_t function,vm::ProgramCounter pc,vm::ProgramCounter bound) {
    if(config_.mode==Mode::off)return bound;
    // A bound at the loop head consults the tier before the next iteration.
    return random_bound(pc,program_.functions[function].end);
}
}
