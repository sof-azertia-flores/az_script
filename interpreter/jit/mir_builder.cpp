#include "mir.h"
#include "shadow.h"
#include <map>
#include <stdexcept>

namespace azertian::jit {
namespace {
using vm::Opcode;
using Kind=RegisterState::Kind;
constexpr std::uint8_t returned_flag=1,broken_flag=2,continued_flag=4;

class Builder {
    const vm::ExecutableProgram& program;
    const FunctionAnalysis& analysis;
    const vm::FunctionRecord& record;
    const bool stress;
    Function out;
    std::uint32_t R=0,L=0;
    // How each instruction lowered in the sizing pass: only a STEP exit, or
    // an exit that is always taken.
    std::vector<bool> stepped,failing;
    // Block leaders of the current pass, and instructions folded into the
    // instruction before them.
    std::vector<bool> leader,fused;
    std::vector<std::uint32_t> pc_op;
    struct Patch {std::uint32_t op;vm::ProgramCounter pc;bool other=false;};
    std::vector<Patch> patches;
    std::map<std::pair<vm::ProgramCounter,ExitKind>,std::uint32_t> sites;

    std::size_t at(vm::ProgramCounter pc) const{return pc-analysis.begin;}
    const FrameState& state(vm::ProgramCounter pc) const{return analysis.states[at(pc)];}
    std::uint32_t emit(Op op) {out.ops.push_back(op);return static_cast<std::uint32_t>(out.ops.size()-1);}
    std::uint32_t site(vm::ProgramCounter pc,ExitKind kind) {
        const auto key=std::make_pair(pc,kind);
        if(const auto found=sites.find(key);found!=sites.end())return found->second;
        const auto& frame=state(pc);
        out.exits.push_back({pc,kind,frame.statement_depth,frame.loop_depth});
        const auto index=static_cast<std::uint32_t>(out.exits.size()-1);
        sites.emplace(key,index);return index;
    }
    Op op(Code code,vm::ProgramCounter pc) const{Op result;result.code=code;result.pc=pc;return result;}
    void exit(vm::ProgramCounter pc,ExitKind kind) {
        auto result=op(Code::exit,pc);result.exit=site(pc,kind);emit(result);
    }
    void step(vm::ProgramCounter pc){stepped[at(pc)]=true;exit(pc,ExitKind::step);}
    void fail(vm::ProgramCounter pc){failing[at(pc)]=true;exit(pc,ExitKind::redo);}
    // The slot holding a register's value, reading a reference into temp
    // when its storage is VM memory; none when the VM must run the read.
    std::uint32_t source(vm::ProgramCounter pc,std::uint32_t reg,std::uint32_t temp) {
        const auto& frame=state(pc);
        const auto& value=frame.registers.at(reg);
        if(read_tags(frame,reg)&~shadowable&&value.kind!=Kind::global&&value.kind!=Kind::orphan)return none;
        switch(value.kind) {
        case Kind::value:return analysis.shadow_registers[reg]?reg:none;
        case Kind::local:
            if(analysis.shadow_locals[value.index])return R+value.index;
            [[fallthrough]];
        case Kind::global:case Kind::orphan: {
            auto read=op(Code::read_reference,pc);read.a=temp;read.b=reg;read.exit=site(pc,ExitKind::step);
            read.tags=read_tags(frame,reg)&shadowable;emit(read);return temp;
        }
        default:return none;
        }
    }
    // Write a value register as VM value() does: drop a reference first.
    void write(vm::ProgramCounter pc,std::uint32_t reg,std::uint32_t slot,TagSet tags) {
        if(state(pc).registers.at(reg).kind!=Kind::value) {
            auto drop=op(Code::drop_reference,pc);drop.a=reg;emit(drop);
        }
        if(slot!=reg) {auto copy=op(Code::copy,pc);copy.a=reg;copy.b=slot;copy.tags=tags;emit(copy);}
    }
    bool writable(std::uint32_t reg) const{return analysis.shadow_registers.at(reg);}
    // Whether a register might still reach a local's cell after its binding ends.
    bool referenced(const FrameState& frame,std::uint32_t local) const {
        for(const auto& reg:frame.registers)
            if((reg.kind==Kind::local&&reg.index==local)||reg.kind==Kind::unknown)return true;
        return false;
    }
    void flush(vm::ProgramCounter pc,std::uint32_t local) {
        if(!analysis.shadow_locals[local]||!referenced(state(pc),local))return;
        auto flush=op(Code::flush_local,pc);flush.a=R+local;flush.b=local;emit(flush);
    }
    void branch(Op branch,vm::ProgramCounter target) {
        const auto index=emit(branch);patches.push_back({index,target});
    }
    // A declared local loaded and at once snapshotted into the same register
    // (the left operand of every binary operation): the VM retains the cell
    // and releases it again while the binding still holds it, so only the
    // register's old content is released, at the load, and the register
    // ends as a copy of the local's value.
    bool fold_snapshot(vm::ProgramCounter pc,int slot) {
        const auto& instruction=program.instructions[pc];
        const auto& frame=state(pc);
        const auto next=pc+1;
        if(slot<0||static_cast<std::size_t>(slot)>=frame.locals.size()||next>=analysis.end||
           !analysis.reached[at(next)]||leader[at(next)])return false;
        const auto& following=program.instructions[next];
        if((following.opcode!=Opcode::snapshot&&following.opcode!=Opcode::materialize)||
           following.left!=instruction.result||following.result!=instruction.result)return false;
        const auto& local=frame.locals[slot];
        if(local.declared!=LocalState::Declared::yes||!analysis.shadow_locals[slot]||!writable(instruction.result))return false;
        write(pc,instruction.result,R+static_cast<std::uint32_t>(slot),local.tags);
        fused[at(next)]=true;return true;
    }
    void lower(vm::ProgramCounter pc) {
        const auto& instruction=program.instructions[pc];
        const auto& frame=state(pc);
        const auto T0=R+L,T1=R+L+1,T2=R+L+2;
        switch(instruction.opcode) {
        case Opcode::tick:emit(op(Code::tick,pc));return;
        case Opcode::statement_enter:case Opcode::statement_leave:case Opcode::loop_enter:case Opcode::loop_leave:return;
        case Opcode::constant: {
            const auto shadow=shadow_of(program.constants[instruction.operand]);
            if(!shadow||!writable(instruction.result))return step(pc);
            auto set=op(Code::set,pc);set.a=T0;set.imm=shadow->bits;set.tag=shadow->tag;set.tags=tag_bit(shadow->tag);emit(set);
            write(pc,instruction.result,T0,set.tags);return;
        }
        case Opcode::load_slot: {
            const int slot=std::bit_cast<int>(instruction.operand);
            if(fold_snapshot(pc,slot))return;
            Op reference=op(slot<0?Code::reference_global:Code::reference_local,pc);
            reference.a=instruction.result;reference.exit=site(pc,ExitKind::redo);
            if(slot<0) {
                const auto relative=static_cast<std::uint32_t>(-static_cast<std::int64_t>(slot)-1);
                if(relative>=record.global_count)return fail(pc);
                reference.b=relative;
            } else {
                if(static_cast<std::size_t>(slot)>=frame.locals.size()||!frame.locals[slot].bound())return fail(pc);
                reference.b=static_cast<std::uint32_t>(slot);
            }
            emit(reference);return;
        }
        case Opcode::check_declaration: {
            const int slot=std::bit_cast<int>(instruction.operand);
            if(slot<0||static_cast<std::size_t>(slot)<record.param_types.size()||static_cast<std::size_t>(slot)>=frame.locals.size()||
               frame.locals[slot].declared==LocalState::Declared::yes)return fail(pc);
            if(frame.locals[slot].declared==LocalState::Declared::maybe) {
                auto check=op(Code::check_undeclared,pc);check.a=static_cast<std::uint32_t>(slot);check.exit=site(pc,ExitKind::redo);emit(check);
            }
            return;
        }
        case Opcode::declare_slot: {
            const auto local=instruction.operand;
            if(!analysis.shadow_locals[local])return step(pc);
            std::uint32_t initial=none;
            if(instruction.left!=vm::no_index&&(initial=source(pc,instruction.left,T1))==none)return step(pc);
            if(frame.locals[local].bound())flush(pc,local);
            auto declare=op(Code::declare,pc);declare.a=local;declare.b=initial;declare.exit=site(pc,ExitKind::step);
            declare.tags=instruction.left==vm::no_index?tag_bit(VOID_VALUE):read_tags(frame,instruction.left);emit(declare);
            return;
        }
        case Opcode::store: {
            const auto& target=frame.registers.at(instruction.left);
            if(target.kind==Kind::value)return fail(pc);
            if(target.kind==Kind::unknown||!writable(instruction.result))return step(pc);
            if(target.kind==Kind::local&&!analysis.shadow_locals[target.index])return step(pc);
            const auto value=source(pc,instruction.right,T1);
            if(value==none)return step(pc);
            const auto tags=read_tags(frame,instruction.right);
            if(target.kind==Kind::local) {
                auto copy=op(Code::copy,pc);copy.a=R+target.index;copy.b=value;copy.tags=tags;emit(copy);
            } else {
                auto store=op(Code::write_reference,pc);store.a=instruction.left;store.b=value;store.exit=site(pc,ExitKind::redo);emit(store);
            }
            write(pc,instruction.result,value,tags);return;
        }
        case Opcode::snapshot:case Opcode::materialize: {
            if(!writable(instruction.result))return step(pc);
            const auto value=source(pc,instruction.left,T1);
            if(value==none)return step(pc);
            write(pc,instruction.result,value,read_tags(frame,instruction.left));return;
        }
        case Opcode::copy_reference: {
            const auto& source_state=frame.registers.at(instruction.left);
            if(source_state.kind==Kind::unknown)return step(pc);
            if(source_state.kind==Kind::value) {
                if(!writable(instruction.result))return step(pc);
                const auto value=source(pc,instruction.left,T1);
                if(value==none)return step(pc);
                write(pc,instruction.result,value,source_state.tags);return;
            }
            auto copy=op(Code::copy_reference,pc);copy.a=instruction.result;copy.b=instruction.left;emit(copy);return;
        }
        case Opcode::truth: {
            if(!writable(instruction.result))return step(pc);
            const auto value=source(pc,instruction.left,T1);
            if(value==none)return step(pc);
            auto truth=op(Code::truth,pc);truth.a=value;truth.exit=site(pc,ExitKind::redo);emit(truth);
            write(pc,instruction.result,value,tag_bit(BOOLEAN_VALUE));return;
        }
        case Opcode::unary:case Opcode::binary:case Opcode::convert: {
            if(!writable(instruction.result))return step(pc);
            TagSet result;
            if(instruction.opcode==Opcode::binary)result=binary_result(static_cast<BinaryOperation>(instruction.operand),
                read_tags(frame,instruction.left),read_tags(frame,instruction.right));
            else if(instruction.opcode==Opcode::unary)result=unary_result(static_cast<UnaryOperation>(instruction.operand),read_tags(frame,instruction.left));
            else result=convert_result(static_cast<int>(instruction.operand),read_tags(frame,instruction.left));
            if(result&~shadowable)return step(pc);
            const auto left=source(pc,instruction.left,T1);
            if(left==none)return step(pc);
            auto compute=op(instruction.opcode==Opcode::binary?Code::binary:instruction.opcode==Opcode::unary?Code::unary:Code::convert,pc);
            compute.a=T0;compute.b=left;compute.exit=site(pc,ExitKind::redo);compute.tags=result;
            if(instruction.opcode==Opcode::binary) {
                const auto right=source(pc,instruction.right,T2);
                if(right==none)return step(pc);
                compute.c=right;
            }
            if(instruction.opcode==Opcode::convert)compute.imm=instruction.operand;
            else compute.tag=static_cast<std::uint8_t>(instruction.operand);
            emit(compute);write(pc,instruction.result,T0,result);return;
        }
        case Opcode::jump: {auto jump=op(Code::jump,pc);branch(jump,instruction.target);return;}
        case Opcode::branch_false: {
            const auto value=source(pc,instruction.left,T1);
            if(value==none)return step(pc);
            auto truth=op(Code::truth,pc);truth.a=value;truth.exit=site(pc,ExitKind::redo);emit(truth);
            auto test=op(Code::branch_false,pc);test.a=value;branch(test,instruction.target);return;
        }
        case Opcode::branch_flow:case Opcode::branch_returned: {
            auto test=op(Code::branch_flags,pc);
            test.tag=instruction.opcode==Opcode::branch_flow?returned_flag|broken_flag|continued_flag:returned_flag;
            branch(test,instruction.target);return;
        }
        case Opcode::scope_enter:emit(op(Code::enter_scope,pc));return;
        case Opcode::scope_leave: {
            std::vector<std::uint32_t> released;
            for(std::uint32_t local=0;local<frame.locals.size();++local) {
                const auto& value=frame.locals[local];
                if(value.bound()&&value.depth!=parameter_depth&&value.depth>=frame.scope_depth)released.push_back(local);
            }
            for(const auto local:released)flush(pc,local);
            emit(op(Code::leave_scope,pc));
            for(const auto local:released){auto forget=op(Code::forget,pc);forget.a=R+local;emit(forget);}
            return;
        }
        case Opcode::loop_next: {
            auto next=op(Code::loop_next,pc);const auto index=emit(next);
            patches.push_back({index,instruction.target});patches.push_back({index,instruction.operand,true});return;
        }
        case Opcode::set_break:case Opcode::set_continue: {
            if(!frame.loop_depth)return fail(pc);
            auto set=op(Code::set_flags,pc);set.tag=instruction.opcode==Opcode::set_break?broken_flag:continued_flag;emit(set);return;
        }
        case Opcode::set_return: {
            const auto value=source(pc,instruction.left,T1);
            if(value==none)return step(pc);
            auto result=op(Code::set_result,pc);result.a=value;result.imm=program.sources[pc].location;emit(result);return;
        }
        default:return step(pc);  // calls, function_end
        }
    }
    void pass() {
        const auto size=analysis.end-analysis.begin;
        // Block leaders: entry, branch targets, after transfers and exits.
        leader.assign(size,stress);fused.assign(size,false);
        leader[0]=true;
        for(auto pc=analysis.begin;pc<analysis.end;++pc) {
            if(!analysis.reached[at(pc)])continue;
            const auto& instruction=program.instructions[pc];
            const auto mark=[&](vm::ProgramCounter target){if(target>=analysis.begin&&target<analysis.end)leader[at(target)]=true;};
            switch(instruction.opcode) {
            case Opcode::jump:mark(instruction.target);mark(pc+1);break;
            case Opcode::branch_false:case Opcode::branch_flow:case Opcode::branch_returned:mark(instruction.target);mark(pc+1);break;
            case Opcode::loop_next:mark(instruction.target);mark(instruction.operand);mark(pc+1);break;
            default:if(stepped[at(pc)]||failing[at(pc)])mark(pc+1);break;
            }
        }
        out.ops.clear();out.exits.clear();sites.clear();patches.clear();
        out.resume.assign(size,none);pc_op.assign(size,none);
        for(auto pc=analysis.begin;pc<analysis.end;++pc) {
            if(!analysis.reached[at(pc)])continue;
            if(stress)exit(pc,ExitKind::yield);
            const auto start=static_cast<std::uint32_t>(out.ops.size());
            if(leader[at(pc)]) {
                out.resume[at(pc)]=start;
                std::uint64_t ticks=0;
                for(auto next=pc;next<analysis.end&&analysis.reached[at(next)];++next) {
                    if(next!=pc&&leader[at(next)])break;
                    if(program.instructions[next].opcode==Opcode::tick)++ticks;
                    if(stepped[at(next)]||failing[at(next)])break;
                }
                if(ticks) {auto check=op(Code::steps,pc);check.imm=ticks;check.exit=site(pc,ExitKind::redo);emit(check);}
            }
            pc_op[at(pc)]=stress?start-1:start;
            if(!fused[at(pc)])lower(pc);
        }
    }
public:
    Builder(const vm::ExecutableProgram& source,const FunctionAnalysis& result,bool stressed)
        :program(source),analysis(result),record(source.functions.at(result.function)),stress(stressed) {
        const auto layout=vm::frame_layout(program,analysis.function);
        R=static_cast<std::uint32_t>(layout.registers);L=static_cast<std::uint32_t>(layout.locals);
    }
    Function build() {
        if(!analysis.eligible)throw std::logic_error("JIT builder given an ineligible function");
        const auto size=analysis.end-analysis.begin;
        out.function=analysis.function;out.registers=R;out.locals=L;out.temporaries=3;
        out.begin=analysis.begin;out.end=analysis.end;out.analysis=&analysis;
        stepped.assign(size,false);failing.assign(size,false);
        // The first pass finds the instructions native code hands to the VM;
        // the second places block budgets with that knowledge.
        pass();pass();
        for(const auto& patch:patches) {
            const auto target=patch.pc>=analysis.begin&&patch.pc<analysis.end?pc_op[at(patch.pc)]:none;
            if(target==none)throw std::logic_error("JIT branch to an unreached instruction");
            (patch.other?out.ops[patch.op].b:out.ops[patch.op].target)=target;
        }
        // Ops are laid out in pc order: an instruction that falls through
        // reached pc+1 normally, so pc+1's ops follow its own.
        out.fill_registers=analysis.shadow_registers;out.fill_locals=analysis.shadow_locals;
        out.resume_tags.resize(out.resume.size());
        for(std::size_t index=0;index<out.resume.size();++index) {
            if(out.resume[index]==none)continue;
            const auto& frame=analysis.states[index];
            std::vector<TagSet> tags(out.slots(),0);
            for(std::uint32_t reg=0;reg<R;++reg)
                if(out.fill_registers[reg]&&frame.registers[reg].kind==Kind::value&&analysis.live[index][reg])
                    tags[reg]=frame.registers[reg].tags;
            for(std::uint32_t local=0;local<L;++local)
                if(out.fill_locals[local]&&frame.locals[local].bound())tags[R+local]=frame.locals[local].tags;
            out.resume_tags[index]=std::move(tags);
        }
        for(const auto& exit:out.exits) {
            std::vector<std::uint32_t> live;
            for(std::uint32_t reg=0;reg<R;++reg)
                if(out.fill_registers[reg]&&analysis.live[at(exit.pc)][reg])live.push_back(reg);
            out.exit_registers.push_back(std::move(live));
        }
        verify(out);
        return std::move(out);
    }
};
}

Function build(const vm::ExecutableProgram& program,const FunctionAnalysis& analysis,bool stress) {
    return Builder(program,analysis,stress).build();
}
void verify(const Function& function) {
    const auto slots=function.slots();
    const auto slot=[&](std::uint32_t index){if(index!=none&&index>=slots)throw std::logic_error("JIT op uses a slot outside its function");};
    for(const auto& op:function.ops) {
        slot(op.a);
        if(op.code!=Code::loop_next)slot(op.b);
        slot(op.c);
        if((op.code==Code::jump||op.code==Code::branch_false||op.code==Code::branch_flags||op.code==Code::loop_next)&&
           op.target>=function.ops.size())throw std::logic_error("JIT branch without a target");
        if(op.code==Code::loop_next&&op.b>=function.ops.size())throw std::logic_error("JIT loop exit without a target");
        if(op.exit!=none&&op.exit>=function.exits.size())throw std::logic_error("JIT op names a missing exit");
        if(op.pc<function.begin||op.pc>=function.end)throw std::logic_error("JIT op outside its function");
    }
    for(const auto resume:function.resume)if(resume!=none&&resume>=function.ops.size())throw std::logic_error("JIT resume point without code");
}
}
namespace azertian::jit {
std::string dump(const Function& function) {
    static constexpr const char* names[]{"steps","tick","set","copy","forget","read_reference","write_reference","truth",
        "branch_false","jump","branch_flags","set_flags","loop_next","binary","unary","convert","reference_local",
        "reference_global","copy_reference","drop_reference","declare","flush_local","enter_scope","leave_scope",
        "check_undeclared","set_result","exit"};
    std::string text="function "+std::to_string(function.function)+": "+std::to_string(function.registers)+" registers, "+
        std::to_string(function.locals)+" locals\n";
    const auto operand=[](std::uint32_t value){return value==none?std::string("-"):std::to_string(value);};
    for(std::size_t index=0;index<function.ops.size();++index) {
        const auto& op=function.ops[index];
        if(function.resume[op.pc-function.begin]==index)text+="resume "+std::to_string(op.pc)+":\n";
        text+="  "+std::to_string(index)+" pc"+std::to_string(op.pc)+" "+names[static_cast<int>(op.code)]+
            " a="+operand(op.a)+" b="+operand(op.b)+" c="+operand(op.c)+" tag="+std::to_string(op.tag)+
            " imm="+std::to_string(op.imm)+" target="+operand(op.target);
        if(op.exit!=none) {
            const auto& exit=function.exits[op.exit];
            text+=std::string(" exit=")+(exit.kind==ExitKind::step?"step":exit.kind==ExitKind::redo?"redo":"yield")+"@"+std::to_string(exit.pc);
        }
        text+="\n";
    }
    return text;
}
}
