#include "jit/tier.h"
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
using namespace azertian;
using namespace azertian::jit;
std::size_t checks=0;
void check(bool condition,const std::string& detail) {
    ++checks;if(!condition)throw std::runtime_error(detail);
}
template<class Action> void rejects(Action action,const std::string& text) {
    try{action();}
    catch(const std::invalid_argument& error) {
        check(std::string(error.what()).find(text)!=std::string::npos,"unexpected message: "+std::string(error.what()));return;
    }
    check(false,"accepted an invalid configuration");
}
void configuration() {
    check(parse_config("")==Config{},"empty text is off");
    check(parse_config("off")==Config{},"off");
    check(parse_config("probe")==Config{Mode::probe,1},"probe uses seed 1");
    check(parse_config("probe:42")==Config{Mode::probe,42},"probe seed");
    check(parse_config("eager").mode==Mode::eager&&parse_config("stress").mode==Mode::stress,"compiling modes");
    rejects([]{parse_config("fast");},"Unknown AZSCRIPT_JIT mode: fast");
    rejects([]{parse_config("eager:1");},"Unknown AZSCRIPT_JIT mode");
    check(parse_config("eager+llvm")==Config{Mode::eager,1,Backend::llvm},"a mode names its backend");
    check(parse_config("stress+interp")==Config{Mode::stress,1,Backend::interp},"the portable backend by name");
    rejects([]{parse_config("probe+llvm");},"Unknown AZSCRIPT_JIT mode");
    check(parse_config("on")==Config{Mode::on,1,Backend::llvm,1000},"the policy compiles with llvm after 1000 events");
    check(parse_config("on:5+interp")==Config{Mode::on,1,Backend::interp,5},"a threshold and the portable backend");
    check(parse_config("require:2")==Config{Mode::require,1,Backend::llvm,2},"require");
    rejects([]{parse_config("on:0");},"Invalid AZSCRIPT_JIT threshold");
    rejects([]{parse_config("on:x");},"Invalid AZSCRIPT_JIT threshold");
    rejects([]{parse_config("stress:3");},"Unknown AZSCRIPT_JIT mode");
    rejects([]{parse_config("eager+");},"Unknown AZSCRIPT_JIT mode: eager+");
    rejects([]{parse_config("eager+gcc");},"Unknown AZSCRIPT_JIT_BACKEND: gcc");
    rejects([]{parse_config("probe:");},"Invalid AZSCRIPT_JIT seed");
    rejects([]{parse_config("probe:x");},"Invalid AZSCRIPT_JIT seed");
    rejects([]{parse_config("probe:1x");},"Invalid AZSCRIPT_JIT seed");
}
// Probing never runs native code, so it never touches the frame.
struct Untouched final:FrameAccess {
    bool register_shadow(std::uint32_t,std::optional<Shadow>&) const noexcept override{std::terminate();}
    void set_register_shadow(std::uint32_t,const Shadow&) noexcept override{std::terminate();}
    bool local_shadow(std::uint32_t,std::optional<Shadow>&) const noexcept override{std::terminate();}
    void set_local_shadow(std::uint32_t,const Shadow&) noexcept override{std::terminate();}
    std::uint8_t flags() const noexcept override{std::terminate();}
    void set_flags(std::uint8_t) noexcept override{std::terminate();}
    std::uint64_t steps() const noexcept override{std::terminate();}
    void set_steps(std::uint64_t) noexcept override{std::terminate();}
    void set_depths(std::uint32_t,std::uint32_t) noexcept override{std::terminate();}
    void set_pc(vm::ProgramCounter) noexcept override{std::terminate();}
    bool reference_local(std::uint32_t,std::uint32_t) noexcept override{std::terminate();}
    bool reference_global(std::uint32_t,std::uint32_t) noexcept override{std::terminate();}
    void copy_reference(std::uint32_t,std::uint32_t) noexcept override{std::terminate();}
    void drop_reference(std::uint32_t) noexcept override{std::terminate();}
    bool read_reference(std::uint32_t,Value&) const noexcept override{std::terminate();}
    bool write_reference(std::uint32_t,const Value&) noexcept override{std::terminate();}
    bool declare(std::uint32_t,const Value&) noexcept override{std::terminate();}
    void enter_scope() noexcept override{std::terminate();}
    void leave_scope() noexcept override{std::terminate();}
    bool undeclared(std::uint32_t) const noexcept override{std::terminate();}
    void set_result(const Value&,std::uint32_t) noexcept override{std::terminate();}
} untouched;
vm::ProgramCounter reached(Tier& tier,std::uint32_t function,vm::ProgramCounter pc) {
    const auto result=tier.reached(function,pc,untouched);
    check(!result.ran,"probing never runs native code");
    return result.bound;
}
vm::ExecutableProgram program() {
    vm::ExecutableProgram result;
    vm::FunctionRecord first;first.begin=0;first.end=5;
    vm::FunctionRecord second;second.begin=5;second.end=6;
    result.functions={first,second};return result;
}
void bounds() {
    const auto source=program();
    Tier off(source,{});
    check(off.entry_bound(0)==5&&off.entry_bound(1)==6,"off frames are bounded by the function end");
    check(!off.reports_backedges(),"off does not report back-edges");
    check(reached(off,0,2)==5,"off never lowers a bound");
    check(off.backedge(0,1,5)==5,"off keeps the bound at a back-edge");
    Tier probe(source,{Mode::probe,7});
    check(probe.entry_bound(0)==0&&probe.entry_bound(1)==5,"probing frames consult the tier at entry");
    check(probe.reports_backedges(),"probe reports back-edges");
    bool lowered=false,raised=false;
    for(int i=0;i<200;++i) {
        const auto pc=static_cast<vm::ProgramCounter>(i%5);
        const auto next=reached(probe,0,pc);
        check(next>pc&&next<=5,"a reached bound moves above pc and stays inside the function");
        const auto back=probe.backedge(0,pc,5);
        check(back>=pc&&back<=5,"a back-edge bound stays between pc and the end");
        lowered|=back==pc;raised|=next==5;
    }
    check(lowered&&raised,"probe bounds cover the loop head and the function end");
    check(reached(probe,1,5)==6,"a one-instruction function's next bound is its end");
    try{reached(probe,0,5);check(false,"a bound at the end is accepted");}
    catch(const std::logic_error&) {++checks;}
    Tier again(source,{Mode::probe,7}),other(source,{Mode::probe,8});
    Tier same(source,{Mode::probe,7});
    bool differs=false;
    for(int i=0;i<64;++i) {
        const auto pc=static_cast<vm::ProgramCounter>(i%4);
        const auto expected=reached(again,0,pc);
        check(reached(same,0,pc)==expected,"a seed fixes the probe sequence");
        differs|=reached(other,0,pc)!=expected;
    }
    check(differs,"seeds change the probe sequence");
}
}
void native_size() {
    Function code;code.registers=8;code.ops.resize(2048);code.resume.assign(2000,none);
    for(std::uint32_t pc=0;pc<1024;++pc)code.resume[pc]=pc;
    check(fits_native(code),"2,048 operations and 1,024 resume points of 8 slots fit");
    code.ops.emplace_back();
    check(!fits_native(code),"one more operation does not fit");
    code.ops.pop_back();code.temporaries=1;
    check(!fits_native(code),"one more slot at every resume point does not fit");
    code.temporaries=0;code.resume[1500]=1500;
    check(!fits_native(code),"one more resume point does not fit");
}
int main() {
    try{configuration();bounds();native_size();std::cout<<"JIT tier checks passed: "<<checks<<'\n';}
    catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
