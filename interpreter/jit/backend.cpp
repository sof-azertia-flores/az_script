#include "backend.h"
#include "operations.h"
#include <stdexcept>

namespace azertian::jit {
namespace {
constexpr std::uint8_t returned_flag=1,broken_flag=2,continued_flag=4;
}
bool fill(const Function& function,vm::ProgramCounter pc,const FrameAccess& frame,Activation& activation,Counters& counters) {
    const auto& state=function.analysis->states.at(pc-function.begin);
    const auto& tags=function.resume_tags.at(pc-function.begin);
    // A value the analysis did not expect at a resume point means the
    // analysis is wrong: count it and let the VM run.
    const auto expected=[&](std::uint32_t slot,const Shadow& shadow) {
        if(tags[slot]&tag_bit(shadow.tag))return true;
        ++counters.violations;return false;
    };
    // Only the slots present on entry are loaded; the rest start absent.
    activation.slots.assign(function.slots(),Shadow{});
    for(std::uint32_t reg=0;reg<function.registers;++reg) {
        if(!tags[reg])continue;
        Value value;
        if(!frame.register_value(reg,value))return false;
        const auto shadow=shadow_of(value);
        if(!shadow||!expected(reg,*shadow))return false;
        activation.slots[reg]=*shadow;
    }
    for(std::uint32_t local=0;local<function.locals;++local) {
        if(!tags[function.registers+local])continue;
        Value value;
        if(!frame.local_value(local,value)) {
            if(state.locals[local].declared==LocalState::Declared::yes)return false;
            continue;
        }
        const auto shadow=shadow_of(value);
        if(!shadow||!expected(function.registers+local,*shadow))return false;
        activation.slots[function.registers+local]=*shadow;
    }
    activation.flags=frame.flags();activation.steps=frame.steps();
    return true;
}
void write_back(const Function& function,std::uint32_t site,FrameAccess& frame,const Activation& activation) noexcept {
    const auto& exit=function.exits[site];
    for(const auto reg:function.exit_registers[site])
        if(activation.slots[reg].tag!=absent)frame.set_register_value(reg,value_of(activation.slots[reg]));
    for(std::uint32_t local=0;local<function.locals;++local)
        if(const auto& shadow=activation.slots[function.registers+local];shadow.tag!=absent)
            frame.set_local_value(local,value_of(shadow));
    frame.set_flags(activation.flags);frame.set_steps(activation.steps);
    frame.set_depths(exit.statement_depth,exit.loop_depth);frame.set_pc(exit.pc);
}
Outcome interpret(const Function& function,vm::ProgramCounter pc,FrameAccess& frame,Counters& counters,Activation& activation) {
    const auto resume=function.resume.at(pc-function.begin);
    if(resume==none)throw std::logic_error("JIT entry at a pc without a resume point");
    if(!fill(function,pc,frame,activation,counters)){++counters.rejects;return {};}
    ++counters.entries;
    auto& V=activation.slots;
    const auto leave=[&](std::uint32_t site) {
        const auto& exit=function.exits[site];
        write_back(function,site,frame,activation);
        switch(exit.kind) {
        case ExitKind::step:++counters.steps;break;
        case ExitKind::redo:++counters.redos;break;
        case ExitKind::yield:++counters.yields;break;
        }
        return Outcome{true,exit.kind,exit.pc};
    };
    for(std::uint32_t at=resume;;) {
        // Past the last op only on paths the analysis proved impossible.
        if(at>=function.ops.size())std::terminate();
        const auto& op=function.ops[at++];
        ++counters.operations;
        switch(op.code) {
        case Code::steps:if(activation.steps<op.imm)return leave(op.exit);break;
        case Code::tick:--activation.steps;break;
        case Code::set:V[op.a]={op.imm,op.tag};break;
        case Code::copy:V[op.a]=V[op.b];break;
        case Code::forget:V[op.a].tag=absent;break;
        case Code::read_reference: {
            Value value;
            if(!frame.read_reference(op.b,value))return leave(op.exit);
            const auto shadow=shadow_of(value);
            if(!shadow)return leave(op.exit);
            V[op.a]=*shadow;break;
        }
        case Code::write_reference:
            if(!frame.write_reference(op.a,value_of(V[op.b])))return leave(op.exit);
            break;
        case Code::truth:if(V[op.a].tag!=BOOLEAN_VALUE)return leave(op.exit);break;
        case Code::branch_false:if(!V[op.a].bits)at=op.target;break;
        case Code::jump:at=op.target;break;
        case Code::branch_flags:if(activation.flags&op.tag)at=op.target;break;
        case Code::set_flags:activation.flags|=op.tag;break;
        case Code::loop_next:
            if(activation.flags&broken_flag){activation.flags&=~broken_flag;at=op.b;}
            else {activation.flags&=~continued_flag;at=op.target;}
            break;
        case Code::binary: {
            const auto result=binary(static_cast<BinaryOperation>(op.tag),V[op.b],V[op.c]);
            if(!result)return leave(op.exit);
            V[op.a]=*result;break;
        }
        case Code::unary: {
            const auto result=unary(static_cast<UnaryOperation>(op.tag),V[op.b]);
            if(!result)return leave(op.exit);
            V[op.a]=*result;break;
        }
        case Code::convert: {
            const auto result=convert(static_cast<int>(op.imm),V[op.b]);
            if(!result)return leave(op.exit);
            V[op.a]=*result;break;
        }
        case Code::reference_local:
            if(!frame.reference_local(op.a,op.b))return leave(op.exit);
            V[op.a].tag=absent;break;
        case Code::reference_global:
            if(!frame.reference_global(op.a,op.b))return leave(op.exit);
            V[op.a].tag=absent;break;
        case Code::copy_reference:frame.copy_reference(op.a,op.b);V[op.a].tag=absent;break;
        case Code::drop_reference:frame.drop_reference(op.a);break;
        case Code::declare: {
            const Shadow initial=op.b==none?Shadow{0,VOID_VALUE}:V[op.b];
            if(!frame.declare(op.a,value_of(initial)))return leave(op.exit);
            V[function.registers+op.a]=initial;break;
        }
        case Code::flush_local:
            if(V[op.a].tag!=absent)frame.set_local_value(op.b,value_of(V[op.a]));
            break;
        case Code::enter_scope:frame.enter_scope();break;
        case Code::leave_scope:frame.leave_scope();break;
        case Code::check_undeclared:if(!frame.undeclared(op.a))return leave(op.exit);break;
        case Code::set_result:
            frame.set_result(value_of(V[op.a]),static_cast<std::uint32_t>(op.imm));
            activation.flags|=returned_flag;break;
        case Code::exit:return leave(op.exit);
        }
        // The written value against the tags the analysis allowed.
        if(op.tags) {
            const auto slot=op.code==Code::declare?function.registers+op.a:op.a;
            if(V[slot].tag==absent||!(op.tags&tag_bit(V[slot].tag)))++counters.violations;
        }
    }
}
}
