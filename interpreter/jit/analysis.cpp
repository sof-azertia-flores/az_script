#include "analysis.h"
#include "../core/types.h"
#include <array>
#include <deque>
#include <stdexcept>

namespace azertian::jit {
namespace {
using vm::Opcode;
using vm::no_index;
using Kind=RegisterState::Kind;
constexpr std::array scalar_tags{INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,BOOLEAN_VALUE,VOID_VALUE,ADDRESS_VALUE};
// One representative per tag. Each operation's result tag depends only on its
// operands' tags; values only decide whether it fails, and these succeed
// wherever the operation can (nonzero divisors, in-range shift counts and
// address offsets, a numeric string).
Value representative(int tag) {
    switch(tag) {
    case INT_VALUE:return Value(3);
    case STRING_VALUE:return Value(std::string("1"));
    case FLOAT_VALUE:return Value(1.5f);
    case DOUBLE_VALUE:return Value(2.5);
    case BOOLEAN_VALUE:return Value(true);
    case ADDRESS_VALUE:return Value(address{8});
    default:return Value{};
    }
}
template<class Operation> TagSet sampled(TagSet operand,Operation operation) {
    TagSet result=0;
    for(const int tag:scalar_tags) {
        if(!(operand&tag_bit(tag)))continue;
        try{result|=tag_bit(operation(representative(tag)).type_tag());}catch(const std::exception&){}
    }
    return result;
}
constexpr std::size_t max_instructions=1u<<16,max_slots=1u<<12;

class Analyzer {
    const vm::ExecutableProgram& program;
    const vm::FunctionRecord& function;
    const vm::FrameLayout& layout;
    FunctionAnalysis& result;
    std::deque<vm::ProgramCounter> work;
public:
    struct Ineligible {std::string reason;};
private:
    [[noreturn]] void refuse(std::string reason){throw Ineligible{std::move(reason)};}
    std::size_t at(vm::ProgramCounter pc) const{return pc-function.begin;}
    void queue(vm::ProgramCounter pc,const FrameState& state) {
        if(pc<function.begin||pc>=function.end)refuse("control flow leaves the function");
        const auto index=at(pc);
        if(!result.reached[index]) {
            result.reached[index]=true;result.states[index]=state;work.push_back(pc);return;
        }
        auto& previous=result.states[index];
        if(previous.scope_depth!=state.scope_depth||previous.statement_depth!=state.statement_depth||
           previous.loop_depth!=state.loop_depth)
            throw std::logic_error("JIT analysis found unequal control depths at a verified join");
        bool changed=false;
        for(std::size_t i=0;i<state.locals.size();++i) {
            auto& local=previous.locals[i];const auto& next=state.locals[i];
            LocalState joined=local;
            if(local.bound()&&next.bound()&&local.depth!=next.depth)refuse("a local is declared at different depths on joining paths");
            if(!local.bound())joined.depth=next.depth;
            if(local.declared!=next.declared)joined.declared=LocalState::Declared::maybe;
            joined.tags|=next.tags;
            if(!(joined==local)){local=joined;changed=true;}
        }
        for(std::size_t i=0;i<state.registers.size();++i) {
            auto& reg=previous.registers[i];const auto& next=state.registers[i];
            RegisterState joined=reg;
            if(reg.kind!=next.kind||(reg.kind!=Kind::value&&reg.kind!=Kind::orphan&&reg.kind!=Kind::unknown&&reg.index!=next.index))
                joined={Kind::unknown,any_scalar,0};
            else joined.tags|=next.tags;
            if(!(joined==reg)){reg=joined;changed=true;}
        }
        if(changed)work.push_back(pc);
    }
    // A local's binding ends: references to its cell no longer read it.
    static void orphan(FrameState& state,std::uint32_t local) {
        for(auto& reg:state.registers)
            if(reg.kind==Kind::local&&reg.index==local)reg={Kind::orphan,any_scalar,0};
    }
    void write_value(FrameState& state,vm::Register reg,TagSet tags) {
        check_register(reg);state.registers[reg]={Kind::value,tags,0};
    }
    void check_register(vm::Register reg) const {
        if(reg>=layout.registers)throw std::logic_error("JIT analysis register outside its frame");
    }
public:
    Analyzer(const vm::ExecutableProgram& source,std::uint32_t index,const vm::FrameLayout& frame,FunctionAnalysis& output)
        :program(source),function(source.functions[index]),layout(frame),result(output) {}
    void run() {
        const auto size=function.end-function.begin;
        if(size>max_instructions||layout.registers>max_slots||layout.locals>max_slots)refuse("function is too large");
        if(!function.cleanups.empty())refuse("cleanup regions");
        result.reached.assign(size,false);result.states.resize(size);
        FrameState entry;
        entry.registers.assign(layout.registers,RegisterState{});
        entry.locals.assign(layout.locals,LocalState{});
        for(std::size_t i=0;i<function.param_types.size();++i) {
            const int type=function.param_types[i];
            entry.locals[i]={LocalState::Declared::yes,parameter_depth,type==ANY_VALUE?any_scalar:tag_bit(type)};
        }
        queue(function.begin,entry);
        while(!work.empty()) {
            const auto pc=work.front();work.pop_front();
            step(pc,result.states[at(pc)]);
        }
        liveness();
        shadows();
    }
    void step(vm::ProgramCounter pc,FrameState state) {
        const auto& instruction=program.instructions[pc];
        const auto next=pc+1;
        switch(instruction.opcode) {
        case Opcode::tick:case Opcode::statement_enter:case Opcode::statement_leave:
        case Opcode::loop_enter:case Opcode::loop_leave:
            if(instruction.opcode==Opcode::statement_enter)++state.statement_depth;
            if(instruction.opcode==Opcode::statement_leave)--state.statement_depth;
            if(instruction.opcode==Opcode::loop_enter)++state.loop_depth;
            if(instruction.opcode==Opcode::loop_leave)--state.loop_depth;
            queue(next,state);return;
        case Opcode::constant:
            write_value(state,instruction.result,tag_bit(program.constants[instruction.operand].type_tag()));
            queue(next,state);return;
        case Opcode::load_slot: {
            check_register(instruction.result);
            const int slot=std::bit_cast<int>(instruction.operand);
            if(slot<0) {
                const auto relative=static_cast<std::uint32_t>(-static_cast<std::int64_t>(slot)-1);
                if(relative>=function.global_count)return;  // always fails
                state.registers[instruction.result]={Kind::global,any_scalar,relative};
            } else {
                if(static_cast<std::size_t>(slot)>=state.locals.size()||!state.locals[slot].bound())return;
                state.registers[instruction.result]={Kind::local,0,static_cast<std::uint32_t>(slot)};
            }
            queue(next,state);return;
        }
        case Opcode::check_declaration: {
            const int slot=std::bit_cast<int>(instruction.operand);
            if(slot<0||static_cast<std::size_t>(slot)<function.param_types.size()||
               static_cast<std::size_t>(slot)>=state.locals.size()||
               state.locals[slot].declared==LocalState::Declared::yes)return;
            queue(next,state);return;
        }
        case Opcode::declare_slot: {
            const auto slot=instruction.operand;
            if(slot>=state.locals.size())throw std::logic_error("JIT analysis declaration outside its frame");
            const TagSet initial=instruction.left==no_index?tag_bit(VOID_VALUE):read_tags(state,instruction.left);
            // A bound local's new cell keeps its place in the declaration
            // list; an unbound one is appended at the current depth.
            auto& local=state.locals[slot];
            if(local.declared==LocalState::Declared::maybe&&local.depth!=state.scope_depth)
                refuse("a local may be redeclared at another depth");
            if(local.bound())orphan(state,slot);
            else local.depth=state.scope_depth;
            local.declared=LocalState::Declared::yes;local.tags=initial;
            queue(next,state);return;
        }
        case Opcode::store: {
            check_register(instruction.left);
            const TagSet value=read_tags(state,instruction.right)&~tag_bit(OBJECT_VALUE);
            const auto& target=state.registers[instruction.left];
            if(target.kind==Kind::value)return;  // not a reference: always fails
            if(target.kind==Kind::local)state.locals[target.index].tags=value;
            // An unknown reference may name any current local's cell; an
            // orphan names none.
            else if(target.kind==Kind::unknown)for(auto& local:state.locals)if(local.bound())local.tags|=value;
            write_value(state,instruction.result,value);
            queue(next,state);return;
        }
        case Opcode::snapshot:case Opcode::materialize:
            write_value(state,instruction.result,read_tags(state,instruction.left));
            queue(next,state);return;
        case Opcode::copy_reference: {
            check_register(instruction.left);check_register(instruction.result);
            const auto source=state.registers[instruction.left];
            if(source.kind==Kind::value)write_value(state,instruction.result,source.tags);
            else state.registers[instruction.result]=source;
            queue(next,state);return;
        }
        case Opcode::truth:
            if(!(read_tags(state,instruction.left)&tag_bit(BOOLEAN_VALUE)))return;
            write_value(state,instruction.result,tag_bit(BOOLEAN_VALUE));
            queue(next,state);return;
        case Opcode::unary: {
            const auto tags=unary_result(static_cast<UnaryOperation>(instruction.operand),read_tags(state,instruction.left));
            if(!tags)return;
            write_value(state,instruction.result,tags);queue(next,state);return;
        }
        case Opcode::binary: {
            const auto tags=binary_result(static_cast<BinaryOperation>(instruction.operand),
                read_tags(state,instruction.left),read_tags(state,instruction.right));
            if(!tags)return;
            write_value(state,instruction.result,tags);queue(next,state);return;
        }
        case Opcode::convert: {
            const auto tags=convert_result(static_cast<int>(instruction.operand),read_tags(state,instruction.left));
            if(!tags)return;
            write_value(state,instruction.result,tags);queue(next,state);return;
        }
        case Opcode::jump:queue(instruction.target,state);return;
        case Opcode::branch_false:
            if(!(read_tags(state,instruction.left)&tag_bit(BOOLEAN_VALUE)))return;
            queue(instruction.target,state);queue(next,state);return;
        case Opcode::branch_flow:case Opcode::branch_returned:
            queue(instruction.target,state);queue(next,state);return;
        case Opcode::scope_enter:++state.scope_depth;queue(next,state);return;
        case Opcode::scope_leave: {
            if(!state.scope_depth)throw std::logic_error("JIT analysis scope leave without a scope");
            for(std::uint32_t slot=0;slot<state.locals.size();++slot) {
                auto& local=state.locals[slot];
                if(!local.bound()||local.depth==parameter_depth||local.depth<state.scope_depth)continue;
                orphan(state,slot);local={};
            }
            --state.scope_depth;
            queue(next,state);return;
        }
        case Opcode::loop_next:
            if(!state.loop_depth)throw std::logic_error("JIT analysis loop continuation outside a loop");
            queue(instruction.target,state);queue(instruction.operand,state);return;
        case Opcode::set_break:case Opcode::set_continue:
            if(!state.loop_depth)return;
            queue(next,state);return;
        case Opcode::call: {
            const auto& operation=function.operations.at(instruction.operand);
            for(const auto argument:operation.arguments)check_register(argument);
            const auto& target=program.targets.at(*operation.target);
            TagSet tags=any_scalar;
            if(target.kind==vm::TargetKind::script&&target.return_type!=ANY_VALUE)tags=tag_bit(target.return_type);
            write_value(state,instruction.result,tags);
            queue(next,state);return;
        }
        case Opcode::reserve_block:write_value(state,instruction.result,tag_bit(VOID_VALUE));queue(next,state);return;
        case Opcode::set_return:
            if(instruction.operand)refuse("owned return");
            check_register(instruction.left);
            queue(next,state);return;
        case Opcode::function_end:
            if(next!=function.end)refuse("function end before the last instruction");
            return;
        default:
            refuse(std::string("unsupported opcode ")+vm::opcode_name(instruction.opcode));
        }
    }
    // Registers whose own value an instruction may read. A reference
    // register's value field is never read: reads go through the referenced
    // storage. An unknown register may be either.
    void reads(const vm::Instruction& instruction,const FrameState& state,std::vector<bool>& live) const {
        const auto value_read=[&](vm::Register reg) {
            if(reg!=no_index&&(state.registers[reg].kind==Kind::value||state.registers[reg].kind==Kind::unknown))live[reg]=true;
        };
        switch(instruction.opcode) {
        case Opcode::declare_slot:value_read(instruction.left);break;
        case Opcode::store:value_read(instruction.right);break;
        case Opcode::snapshot:case Opcode::materialize:case Opcode::truth:case Opcode::unary:
        case Opcode::convert:case Opcode::branch_false:case Opcode::set_return:case Opcode::copy_reference:
            value_read(instruction.left);break;
        case Opcode::binary:value_read(instruction.left);value_read(instruction.right);break;
        case Opcode::call:
            for(const auto argument:function.operations[instruction.operand].arguments)value_read(argument);
            break;
        default:break;
        }
    }
    static vm::Register written(const vm::Instruction& instruction) {
        switch(instruction.opcode) {
        case Opcode::constant:case Opcode::load_slot:case Opcode::store:case Opcode::snapshot:case Opcode::materialize:
        case Opcode::copy_reference:case Opcode::truth:case Opcode::unary:case Opcode::binary:case Opcode::convert:
        case Opcode::call:case Opcode::reserve_block:
            return instruction.result;
        default:return no_index;
        }
    }
    std::vector<vm::ProgramCounter> successors(vm::ProgramCounter pc) const {
        const auto& instruction=program.instructions[pc];
        switch(instruction.opcode) {
        case Opcode::jump:return {instruction.target};
        case Opcode::branch_false:case Opcode::branch_flow:case Opcode::branch_returned:return {instruction.target,pc+1};
        case Opcode::loop_next:return {instruction.target,instruction.operand};
        case Opcode::function_end:return {};
        default:return {pc+1};
        }
    }
    // Backward liveness of register values over the reachable instructions.
    void liveness() {
        const auto size=function.end-function.begin;
        result.live.assign(size,std::vector<bool>(layout.registers,false));
        for(bool changed=true;changed;) {
            changed=false;
            for(auto pc=function.end;pc-->function.begin;) {
                if(!result.reached[at(pc)])continue;
                std::vector<bool> live(layout.registers,false);
                for(const auto successor:successors(pc))
                    if(successor<function.end&&result.reached[at(successor)])
                        for(std::size_t i=0;i<live.size();++i)live[i]=live[i]||result.live[at(successor)][i];
                const auto& instruction=program.instructions[pc];
                if(const auto reg=written(instruction);reg!=no_index)live[reg]=false;
                reads(instruction,result.states[at(pc)],live);
                if(live!=result.live[at(pc)]){result.live[at(pc)]=std::move(live);changed=true;}
            }
        }
    }
    void shadows() {
        result.shadow_registers.assign(layout.registers,true);
        result.shadow_locals.assign(layout.locals,true);
        for(std::size_t index=0;index<result.states.size();++index) {
            if(!result.reached[index])continue;
            const auto& state=result.states[index];
            for(std::size_t i=0;i<state.registers.size();++i)
                if(state.registers[i].kind==Kind::value&&(state.registers[i].tags&~shadowable))result.shadow_registers[i]=false;
            for(std::size_t i=0;i<state.locals.size();++i)
                if(state.locals[i].bound()&&(state.locals[i].tags&~shadowable))result.shadow_locals[i]=false;
        }
    }
};
}

TagSet binary_result(BinaryOperation operation,TagSet left,TagSet right) {
    TagSet result=0;
    for(const int tag:scalar_tags) {
        if(!(right&tag_bit(tag)))continue;
        const auto other=representative(tag);
        result|=sampled(left,[&](const Value& value){return value_ops::binary(operation,value,other);});
    }
    return result;
}
TagSet unary_result(UnaryOperation operation,TagSet operand) {
    return sampled(operand,[&](const Value& value){return value_ops::unary(operation,value);});
}
TagSet convert_result(int target,TagSet operand) {
    return sampled(operand,[&](const Value& value){return value_ops::convert(value,target);});
}
TagSet read_tags(const FrameState& state,std::uint32_t reg) {
    if(reg>=state.registers.size())throw std::logic_error("JIT analysis register outside its frame");
    const auto& value=state.registers[reg];
    switch(value.kind) {
    case Kind::value:return value.tags;
    case Kind::local:return state.locals.at(value.index).tags;
    default:return any_scalar;
    }
}
FunctionAnalysis analyze(const vm::ExecutableProgram& program,std::uint32_t function,
                         const vm::FrameLayout& layout,bool object_frame) {
    FunctionAnalysis result;
    result.function=function;
    const auto& record=program.functions.at(function);
    result.begin=record.begin;result.end=record.end;
    if(object_frame){result.reason="object frame";return result;}
    if(record.lifecycle){result.reason="lifecycle function";return result;}
    try {
        Analyzer analyzer(program,function,layout,result);analyzer.run();
        result.eligible=true;
    } catch(const Analyzer::Ineligible& refused) {
        result=FunctionAnalysis{};result.function=function;result.begin=record.begin;result.end=record.end;
        result.reason=refused.reason;
    }
    return result;
}
}
