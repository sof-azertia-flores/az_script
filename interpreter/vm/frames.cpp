#include "frames.h"
#include <algorithm>
#include <exception>

namespace azertian::vm {
void CallDepthAccounting::check_can_enter() const {
    if(active_calls_>=max_call_depth_)throw std::runtime_error("Script call depth limit exceeded");
}
CallDepthAccounting::Admission::Admission(CallDepthAccounting& owner) {
    owner.check_can_enter();
    if(!owner.active_calls_)owner.remaining_steps_=owner.max_steps_;
    ++owner.active_calls_;owner_=&owner;
}
void CallDepthAccounting::leave() noexcept {
    if(!active_calls_)std::terminate();
    --active_calls_;
}
CallDepthAccounting::Admission& CallDepthAccounting::Admission::operator=(Admission&& other) noexcept {
    if(this!=&other){reset();owner_=std::exchange(other.owner_,nullptr);}return *this;
}
void CallDepthAccounting::Admission::reset() noexcept {
    if(owner_){auto* owner=std::exchange(owner_,nullptr);owner->leave();}
}
FrameLayout frame_layout(const ExecutableProgram& program,std::uint32_t index) {
    if(index>=program.functions.size())throw std::out_of_range("VM function index is outside its program");
    const auto& function=program.functions[index];
    if(function.param_types.size()>no_index-function.local_count)
        throw std::length_error("VM frame local count exceeds its index space");
    FrameLayout result;
    result.registers=function.register_count;
    result.locals=function.param_types.size()+static_cast<std::size_t>(function.local_count);
    if(result.registers>=no_index||result.locals>=no_index||result.registers>=no_index-result.locals)
        throw std::length_error("VM frame alias storage exceeds its index space");
    result.alias_cells=result.registers+result.locals+1;
    if(function.begin>=function.end||function.end>program.instructions.size())
        throw std::invalid_argument("VM function instruction range is invalid");
    for(auto pc=function.begin;pc<function.end;++pc) {
        const auto opcode=program.instructions[pc].opcode;
        // The verifier requires identical scope stacks at CFG joins, so one
        // scope-enter PC cannot be active twice simultaneously. Its count is a
        // safe bound even when branches reorder the physical instruction stream.
        if(opcode==Opcode::scope_enter)++result.scope_marks;
    }
    return result;
}
FrameStack::FrameStack(WorkspaceBudget& workspace,std::uint32_t max_frames,std::pmr::memory_resource* resource)
    :frames_(workspace,max_frames,resource),stack_(workspace,max_frames,resource) {}
FrameId FrameStack::push(FrameRecord frame) {
    stack_.prepare_append(1);
    const auto id=frames_.emplace(std::move(frame));
    stack_.emplace_back(id); // Capacity and nonthrowing ID construction were prepared above.
    return id;
}
bool FrameStack::pop() noexcept {
    if(stack_.empty())return false;
    const auto id=stack_.back();stack_.pop_back();
    if(!frames_.retire(id))std::terminate();
    return true;
}
FrameRecord& FrameStack::top() {
    if(stack_.empty())throw std::logic_error("VM call stack is empty");
    return frames_.resolve(stack_.back());
}
const FrameRecord& FrameStack::top() const {
    if(stack_.empty())throw std::logic_error("VM call stack is empty");
    return frames_.resolve(stack_.back());
}
}
