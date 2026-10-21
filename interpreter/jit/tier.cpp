#include "tier.h"
#include "../vm/frames.h"
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

namespace azertian::jit {
namespace {
constexpr std::size_t operation_limit=std::size_t{1}<<22;
// LLVM's compile time grows faster than linearly with a function's
// operations and with its resume entries (resume points times slots). With
// only live slots written back and the fast code generator above 2,048
// operations, 6,100 operations take about 0.7 s (3.6 s in stress mode, where
// every pc resumes) and a 43,000-entry stress function 0.4 s.
constexpr std::size_t native_operation_limit=6144,native_resume_limit=65536;
}
bool fits_native(const Function& code) noexcept {
    std::size_t resumes=0;
    for(const auto op:code.resume)resumes+=op!=none;
    return code.ops.size()<=native_operation_limit&&resumes*code.slots()<=native_resume_limit;
}
Tier::Tier(const vm::ExecutableProgram& program,const Config& config)
    :program_(program),config_(config),random_(config.seed) {
    if(config.backend==Backend::llvm&&config.mode!=Mode::off&&config.mode!=Mode::probe) {
        std::string error;
        native_=native_backend(error);
        // On runs without the tier when the module is missing; every
        // other mode that asks for it fails instead of testing nothing.
        if(!native_&&config.mode==Mode::on){config_.mode=Mode::off;unavailable_=error;++refusals_["module unavailable: "+error];}
        else if(!native_)throw std::runtime_error("The LLVM JIT backend is unavailable: "+error);
    }
    entry_bounds_.reserve(program.functions.size());
    // A probing frame consults the tier at its first instruction; compiling
    // modes start that way too, until a function proves ineligible.
    for(const auto& function:program.functions)
        entry_bounds_.push_back(config_.mode==Mode::off?function.end:function.begin);
    backedges_=config_.mode!=Mode::off;
    functions_.resize(program.functions.size());
    heat_.assign(program.functions.size(),0);
}
bool Tier::policy() const noexcept{return config_.mode==Mode::on||config_.mode==Mode::require;}
// Under the policy a function compiles once its entries and back-edges
// reach the threshold; every event before that is interpreted as before.
bool Tier::hot(std::uint32_t function) noexcept {
    if(!policy()||functions_[function].state!=Compiled::State::untried)return true;
    return ++heat_[function]>=config_.threshold;
}
Tier::~Tier() {
    // Statistics side channel for tests: one JSON line per session.
    const char* path=std::getenv("AZSCRIPT_JIT_STATS");
    if(!path||!*path||config_.mode==Mode::probe||(config_.mode==Mode::off&&unavailable_.empty()))return;
    std::size_t ready=0,ineligible=0;
    for(const auto& function:functions_) {
        ready+=function.state==Compiled::State::ready;ineligible+=function.state==Compiled::State::ineligible;
    }
    if(FILE* file=std::fopen(path,"a")) {
        std::fprintf(file,"{\"functions\":%zu,\"compiled\":%zu,\"ineligible\":%zu,\"entries\":%llu,\"rejects\":%llu,"
            "\"steps\":%llu,\"redos\":%llu,\"yields\":%llu,\"operations\":%llu,\"violations\":%llu,\"refusals\":{",
            functions_.size(),ready,ineligible,
            static_cast<unsigned long long>(counters_.entries),static_cast<unsigned long long>(counters_.rejects),
            static_cast<unsigned long long>(counters_.steps),static_cast<unsigned long long>(counters_.redos),
            static_cast<unsigned long long>(counters_.yields),static_cast<unsigned long long>(counters_.operations),
            static_cast<unsigned long long>(counters_.violations));
        bool first=true;
        for(const auto& [reason,count]:refusals_) {
            std::string text;
            for(const unsigned char c:reason) {
                if(c=='"'||c=='\\'){text+='\\';text+=static_cast<char>(c);}
                else if(c<0x20)text+=' ';
                else text+=static_cast<char>(c);
            }
            std::fprintf(file,"%s\"%s\":%llu",first?"":",",text.c_str(),static_cast<unsigned long long>(count));first=false;
        }
        std::fprintf(file,"}}\n");std::fclose(file);
    }
}
void Tier::classify(const std::vector<bool>& object_frames) {
    object_frames_=object_frames;
}
// splitmix64: a fixed sequence for a seed, so a probe run is reproducible.
vm::ProgramCounter Tier::random_bound(vm::ProgramCounter low,vm::ProgramCounter end) noexcept {
    auto z=(random_+=0x9e3779b97f4a7c15ULL);
    z=(z^(z>>30))*0xbf58476d1ce4e5b9ULL;z=(z^(z>>27))*0x94d049bb133111ebULL;z^=z>>31;
    return low+static_cast<vm::ProgramCounter>(z%(end-low+1));
}
Tier::Compiled& Tier::compiled(std::uint32_t function) {
    auto& entry=functions_[function];
    if(entry.state!=Compiled::State::untried)return entry;
    entry.state=Compiled::State::ineligible;
    try {
        const bool object=function<object_frames_.size()?object_frames_[function]:true;
        entry.analysis=analyze(program_,function,vm::frame_layout(program_,function),object);
        if(!entry.analysis.eligible) {
            ++refusals_[entry.analysis.reason];
        } else {
            entry.code=build(program_,entry.analysis,config_.mode==Mode::stress);
            entry.code.analysis=&entry.analysis;
            // A session compiles a bounded amount of code; the rest stays
            // interpreted.
            if(compiled_operations_+entry.code.ops.size()>operation_limit)throw std::length_error("session code limit reached");
            compiled_operations_+=entry.code.ops.size();
            if(const char* path=std::getenv("AZSCRIPT_JIT_DUMP");path&&*path)
                if(FILE* file=std::fopen(path,"a")){const auto text=dump(entry.code);std::fputs(text.c_str(),file);std::fclose(file);}
            if(native_&&!fits_native(entry.code)) {
                ++refusals_["too large for native code"];
                entry.state=Compiled::State::ineligible;entry_bounds_[function]=program_.functions[function].end;
                compiled_operations_-=entry.code.ops.size();
                return entry;
            }
            if(native_) {
                entry.native=std::make_unique<NativeCode>(*native_,entry.code);
                if(!entry.native->compiled())throw std::runtime_error("native code: "+entry.native->error);
            }
            const auto size=entry.analysis.end-entry.analysis.begin;
            entry.next_resume.assign(size,entry.analysis.end);
            auto next=entry.analysis.end;
            for(auto index=size;index-->0;) {
                entry.next_resume[index]=next;
                if(entry.code.resume[index]!=none)next=entry.analysis.begin+static_cast<vm::ProgramCounter>(index);
            }
            entry.state=Compiled::State::ready;
        }
    } catch(const std::exception& error) {
        // A failure to compile only means the function stays interpreted.
        ++refusals_[std::string("compiler error: ")+error.what()];
    }
    if(entry.state!=Compiled::State::ready)entry_bounds_[function]=program_.functions[function].end;
    return entry;
}
vm::ProgramCounter Tier::after(const Compiled& entry,vm::ProgramCounter pc) const noexcept {
    return entry.next_resume[pc-entry.analysis.begin];
}
Tier::Reached Tier::reached(std::uint32_t function,vm::ProgramCounter pc,FrameAccess& frame) {
    const auto end=program_.functions[function].end;
    if(pc>=end)throw std::logic_error("JIT tier consulted at the end of a function");
    switch(config_.mode) {
    case Mode::off:return {end};
    case Mode::probe:return {random_bound(pc+1,end)};
    default:break;
    }
    if(!hot(function))return {end};
    auto& entry=compiled(function);
    if(entry.state!=Compiled::State::ready)return {end};
    if(entry.code.resume[pc-entry.analysis.begin]==none)return {after(entry,pc)};
    const auto outcome=entry.native?entry.native->run(entry.code,pc,frame,counters_,scratch_):interpret(entry.code,pc,frame,counters_,scratch_);
    if(!outcome.entered)return {after(entry,pc)};
    switch(outcome.kind) {
    case ExitKind::yield:return {outcome.pc,true};
    default:return {after(entry,outcome.pc),true};
    }
}
vm::ProgramCounter Tier::backedge(std::uint32_t function,vm::ProgramCounter pc,vm::ProgramCounter bound) {
    switch(config_.mode) {
    case Mode::off:return bound;
    // A bound at the loop head consults the tier before the next iteration.
    case Mode::probe:return random_bound(pc,program_.functions[function].end);
    default:break;
    }
    // A loop of a compiled function continues natively from its head (on-
    // stack replacement needs nothing more: the frame is the VM's).
    if(!hot(function))return bound;
    const auto& entry=compiled(function);
    if(entry.state==Compiled::State::ready&&pc<entry.analysis.end&&entry.code.resume[pc-entry.analysis.begin]!=none)return pc;
    return bound;
}
}
