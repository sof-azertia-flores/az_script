#include "execution.h"
#include "cleanup.h"
#include "operations.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <utility>

namespace azertian::vm {
namespace {
constexpr std::array supported{
    Opcode::tick,Opcode::constant,Opcode::load_slot,Opcode::check_declaration,
    Opcode::declare_slot,Opcode::store,Opcode::snapshot,Opcode::materialize,
    Opcode::copy_reference,Opcode::truth,Opcode::unary,Opcode::binary,Opcode::convert,
    Opcode::jump,Opcode::branch_false,Opcode::branch_flow,Opcode::branch_returned,
    Opcode::scope_enter,Opcode::scope_leave,Opcode::statement_enter,Opcode::statement_leave,
    Opcode::loop_enter,Opcode::loop_next,Opcode::loop_leave,Opcode::set_break,
    Opcode::set_continue,Opcode::call,Opcode::set_return,Opcode::function_end,
    Opcode::cleanup_enter,Opcode::cleanup_leave,Opcode::cleanup_end
};

std::string function_text(int id) {
    char text[16];std::snprintf(text,sizeof text,"0x%08x",static_cast<unsigned>(static_cast<std::uint32_t>(id)));
    return text;
}
void require_scalar_value(const Value& value) {
    if(value.type_tag()==OBJECT_VALUE)
        throw std::invalid_argument("Scalar VM does not support object values");
}
template<class Argument> void validate_arguments(const FunctionRecord& function,
        std::size_t count,Argument argument) {
    const auto& expected=function.param_types;
    if(count!=expected.size())
        throw std::runtime_error("Script function "+function_text(function.function_id)+" argument count mismatch: expected "+
            std::to_string(expected.size())+", got "+std::to_string(count));
    for(std::size_t i=0;i<count;++i) {
        const auto& value=argument(i);require_scalar_value(value);
        const auto got=value.type_tag();
        if(expected[i]!=got&&expected[i]!=ANY_VALUE)
            throw std::runtime_error("Script function "+function_text(function.function_id)+" argument "+std::to_string(i+1)+
                " type mismatch: expected "+type_name(expected[i])+", got "+type_name(got));
    }
}
ExecutionPosition position_of(const FrameRecord& frame) noexcept {
    return {frame.function,frame.node,frame.location,frame.return_location,
        frame.pc==no_index?no_index:frame.pc-1};
}
}

std::span<const Opcode> scalar_capabilities() noexcept{return supported;}

struct ScalarExecutor::State {
    // Register aliases retain storage independently of a lexical name. Cell
    // indices survive vector growth and are recycled only after the last local
    // binding/register alias releases them. Script parameters are snapshots.
    struct Cell {
        SlotId slot;
        std::uint32_t references=0,next=no_index;
    };
    struct RegisterValue {
        Value value;
        SlotId global;
        std::uint32_t cell=no_index;
        bool reference=false;
    };
    const ExecutableProgram& program;
    SlotPool& pool;
    WorkspaceBudget& workspace;
    WorkspaceVector<RegisterValue> registers;
    WorkspaceVector<Cell> cells;
    WorkspaceVector<std::uint32_t> locals,declared,scopes;
    FrameStack frames;
    WorkspaceVector<FrameLayout> layouts;
    // Compiler cleanup continuations for every active region of every frame,
    // innermost last. Each frame reserves its own bound before publication,
    // so entering a region and recovering from a failure never allocate.
    CompilerCleanupStack regions;
    std::uint32_t free_cell=no_index;

    State(const ExecutableProgram& source,SlotPool& storage,WorkspaceBudget& budget)
        :program(source),pool(storage),workspace(budget),registers(budget),cells(budget,no_index),
         locals(budget),declared(budget),scopes(budget),frames(budget),layouts(budget),regions(budget) {
        verify_program(program);
        require_capabilities(program,scalar_capabilities());
        for(const auto& constant:program.constants)require_scalar_value(constant);
        layouts.reserve(program.functions.size());
        for(std::uint32_t index=0;index<program.functions.size();++index) {
            const auto& function=program.functions[index];
            // Lifecycle hooks are internal entries but have no contexts or
            // object signature. Constructors, destructors and factories remain
            // internal object entries until the object VM supports them.
            if(function.hidden_count||function.bound_context_count||(function.entry_kind&&!function.lifecycle))
                throw std::invalid_argument("Scalar VM does not support generic or object function contexts");
            if(function.return_type==OBJECT_VALUE||
                    std::find(function.param_types.begin(),function.param_types.end(),OBJECT_VALUE)!=function.param_types.end())
                throw std::invalid_argument("Scalar VM does not support object function signatures");
            layouts.emplace_back(frame_layout(program,index));
            for(auto pc=function.begin;pc<function.end;++pc) {
                const auto& instruction=program.instructions[pc];
                if(instruction.opcode==Opcode::set_return&&instruction.operand!=0)
                    throw std::invalid_argument("Scalar VM does not support owned returns");
                if(instruction.opcode==Opcode::call) {
                    const auto& operation=function.operations[instruction.operand];
                    const auto& target=program.targets[*operation.target];
                    if(target.hidden_count||target.entry_kind||!operation.contexts.empty())
                        throw std::invalid_argument("Scalar VM does not support generic or object call contexts");
                    if(target.return_type==OBJECT_VALUE||
                            std::find(target.param_types.begin(),target.param_types.end(),OBJECT_VALUE)!=target.param_types.end())
                        throw std::invalid_argument("Scalar VM does not support object call signatures");
                }
            }
        }
    }
    void release(std::uint32_t cell) noexcept {
        auto& binding=cells[cell];
        if(--binding.references)return;
        if(!pool.retire(binding.slot))std::terminate();
        binding.slot={};binding.next=free_cell;free_cell=cell;
    }
    void clear(RegisterValue& reg) noexcept {
        if(reg.reference&&reg.cell!=no_index)release(reg.cell);
        reg.value=Value{};reg.global={};reg.cell=no_index;reg.reference=false;
    }
    const Value& read(const FrameRecord& frame,Register index) const {
        const auto& reg=registers[frame.registers.begin+index];
        if(!reg.reference)return reg.value;
        return pool.resolve(reg.cell==no_index?reg.global:cells[reg.cell].slot);
    }
    void value(const FrameRecord& frame,Register destination,Value next) noexcept {
        auto& reg=registers[frame.registers.begin+destination];clear(reg);reg.value=std::move(next);
    }
    void reference(const FrameRecord& frame,Register destination,SlotId global,std::uint32_t cell) noexcept {
        // Retain before clearing: copy_reference may overwrite its own input.
        if(cell!=no_index)++cells[cell].references;
        auto& reg=registers[frame.registers.begin+destination];clear(reg);
        reg.reference=true;reg.global=global;reg.cell=cell;
    }
    void copy(const FrameRecord& frame,Register destination,Register source) noexcept {
        const auto& reg=registers[frame.registers.begin+source];
        if(reg.reference)reference(frame,destination,reg.global,reg.cell);
        else value(frame,destination,reg.value);
    }
    std::uint32_t allocate(Value initial) {
        if(free_cell==no_index) {
            // Capacity may grow, but no cell or SlotId becomes visible until
            // both allocations succeed. Cached empty capacity stays charged.
            cells.prepare_append(1);
            const auto slot=pool.emplace(std::move(initial));
            const auto index=static_cast<std::uint32_t>(cells.size());
            cells.emplace_back(Cell{slot,1,no_index});return index;
        }
        const auto cell=free_cell;
        const auto slot=pool.emplace(std::move(initial));
        auto& binding=cells[cell];free_cell=binding.next;
        binding.slot=slot;binding.references=1;binding.next=no_index;return cell;
    }
    std::uint32_t& local(const FrameRecord& frame,std::size_t index) noexcept {
        return locals[frame.locals.begin+index];
    }
    void check_declaration(const FrameRecord& frame,int index,const FunctionRecord& function) const {
        if(index<0||static_cast<std::size_t>(index)<function.param_types.size()||
                static_cast<std::size_t>(index)>=frame.locals.count)
            throw std::runtime_error("Invalid local variable declaration: "+std::to_string(index));
        if(locals[frame.locals.begin+static_cast<std::size_t>(index)]!=no_index)
            throw std::runtime_error("Duplicate variable slot: "+std::to_string(index));
    }
    void load(const FrameRecord& frame,Register destination,int index,
            const FunctionRecord& function,std::span<const SlotId> globals) {
        if(index<0) {
            const auto relative=static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1);
            if(relative>=function.global_count||function.global_offset>globals.size()||
                    relative>=globals.size()-function.global_offset)
                throw std::runtime_error("Global variable slot is outside its module: "+std::to_string(index));
            reference(frame,destination,globals[function.global_offset+static_cast<std::size_t>(relative)],no_index);return;
        }
        if(static_cast<std::size_t>(index)>=frame.locals.count)
            throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
        const auto cell=local(frame,static_cast<std::size_t>(index));
        if(cell==no_index)
            throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
        reference(frame,destination,{},cell);
    }
    void store(const FrameRecord& frame,Register destination,Register target,Value next) {
        auto& reg=registers[frame.registers.begin+target];
        if(!reg.reference)throw std::logic_error("Scalar VM store target is not a slot reference");
        pool.resolve(reg.cell==no_index?reg.global:cells[reg.cell].slot)=next;
        value(frame,destination,std::move(next));
    }
    void leave_scope(FrameRecord& frame) noexcept {
        const auto mark=scopes[frame.scope_marks.begin+--frame.scope_count];
        while(frame.declared_count>mark) {
            const auto index=declared[frame.declared.begin+--frame.declared_count];
            release(local(frame,index));local(frame,index)=no_index;
        }
    }
    void cleanup(FrameRecord& frame) noexcept {
        for(std::size_t i=0;i<frame.registers.count;++i)clear(registers[frame.registers.begin+i]);
        for(std::size_t i=0;i<frame.locals.count;++i)if(local(frame,i)!=no_index)release(local(frame,i));
        registers.truncate(frame.registers.begin);locals.truncate(frame.locals.begin);
        declared.truncate(frame.declared.begin);scopes.truncate(frame.scope_marks.begin);
    }
    template<class Argument> void enter(std::uint32_t index,std::size_t count,Argument argument,
            FrameId caller,Register destination,ExecutionPosition& position,ExecutionOptions options,bool admit) {
        const ExecutionPosition entry{index,0,0,0,no_index};
        try {
            FrameRecord frame;frame.function=index;frame.caller=caller;frame.return_register=destination;
            if(admit&&options.calls)frame.admission=options.calls->enter();
            const auto& function=program.functions[index];
            validate_arguments(function,count,argument);
            const auto layout=layouts[index];
            frame.pc=function.begin;
            frame.registers={registers.size(),layout.registers};frame.locals={locals.size(),layout.locals};
            frame.declared={declared.size(),layout.locals};frame.scope_marks={scopes.size(),layout.scope_marks};
            // Preflight every backing array before publishing any live range.
            // Argument readers use offsets, so relocating caller registers is safe.
            registers.prepare_append(layout.registers);locals.prepare_append(layout.locals);
            declared.prepare_append(layout.locals);scopes.prepare_append(layout.scope_marks);
            regions.prepare(layout.cleanups);
            registers.append_default(layout.registers);locals.append_default(layout.locals);
            declared.append_default(layout.locals);scopes.append_default(layout.scope_marks);
            for(std::size_t i=0;i<layout.locals;++i)local(frame,i)=no_index;
            try {
                for(std::size_t i=0;i<count;++i)local(frame,i)=allocate(argument(i));
                frames.push(std::move(frame));
            } catch(...) {cleanup(frame);throw;}
        } catch(...) {
            position=entry;
            if(options.callbacks)options.callbacks->note_script_error(entry);
            throw;
        }
    }
    Value invoke(std::uint32_t index,std::span<const Value> arguments,std::span<const SlotId> globals,
            std::uint64_t& steps,ExecutionPosition& position,ExecutionOptions options) {
        if(index>=program.functions.size())throw std::out_of_range("Scalar VM function index is outside its program");
        position={index,0,0,0,no_index};
        try {for(const auto global:globals)require_scalar_value(pool.resolve(global));}
        catch(...) {if(options.callbacks)options.callbacks->note_script_error(position);throw;}
        // Native host reentry starts a separate invocation root above this mark.
        // Script CALL never calls invoke recursively and always sets caller.
        const auto mark=frames.size();
        const auto region_mark=regions.size();
        // Entry failures leave no frame of this root; enter reports them.
        enter(index,arguments.size(),[&](std::size_t i)->const Value&{return arguments[i];},
            {},no_index,position,options,options.admit_entry);
        // A failure resumed after its finalizer is raised again inside the
        // dispatch try, so outer regions handle it without native recursion.
        pending_error resumed;
        for(;;) try {
            if(resumed)std::exchange(resumed,pending_error{}).rethrow();
            while(frames.size()>mark) {
                auto& frame=frames.top();
                const auto& function=program.functions[frame.function];
                if(frame.pc>=function.end)throw std::logic_error("Scalar VM function did not terminate");
                const auto& instruction=program.instructions[frame.pc];
                const auto& source=program.sources[frame.pc];
                frame.node=source.node;frame.location=source.location;++frame.pc;
                position=position_of(frame);
                switch(instruction.opcode) {
                case Opcode::tick:
                    if(!steps)throw std::runtime_error("Script execution step limit exceeded");
                    --steps;break;
                case Opcode::constant:value(frame,instruction.result,program.constants[instruction.operand]);break;
                case Opcode::load_slot:load(frame,instruction.result,std::bit_cast<int>(instruction.operand),function,globals);break;
                case Opcode::check_declaration:check_declaration(frame,std::bit_cast<int>(instruction.operand),function);break;
                case Opcode::declare_slot: {
                    const auto slot=instruction.operand;
                    // A hand-written initializer may declare this slot after
                    // the outer declaration's check; replace its storage then.
                    Value initial=instruction.left==no_index?Value{}:read(frame,instruction.left);
                    const auto cell=allocate(std::move(initial));
                    if(local(frame,slot)!=no_index)release(local(frame,slot));
                    else declared[frame.declared.begin+frame.declared_count++]=slot;
                    local(frame,slot)=cell;break;
                }
                case Opcode::store:store(frame,instruction.result,instruction.left,read(frame,instruction.right));break;
                case Opcode::snapshot:case Opcode::materialize:value(frame,instruction.result,read(frame,instruction.left));break;
                case Opcode::copy_reference:copy(frame,instruction.result,instruction.left);break;
                case Opcode::truth:value(frame,instruction.result,Value(vm::truth(read(frame,instruction.left))));break;
                case Opcode::unary:value(frame,instruction.result,vm::unary(static_cast<UnaryOperation>(instruction.operand),read(frame,instruction.left)));break;
                case Opcode::binary:value(frame,instruction.result,vm::binary(static_cast<BinaryOperation>(instruction.operand),read(frame,instruction.left),read(frame,instruction.right)));break;
                case Opcode::convert:value(frame,instruction.result,vm::convert(read(frame,instruction.left),static_cast<int>(instruction.operand)));break;
                case Opcode::jump:frame.pc=instruction.target;break;
                case Opcode::branch_false:if(!vm::truth(read(frame,instruction.left)))frame.pc=instruction.target;break;
                case Opcode::branch_flow:if(frame.returned||frame.broken||frame.continued)frame.pc=instruction.target;break;
                case Opcode::branch_returned:if(frame.returned)frame.pc=instruction.target;break;
                case Opcode::scope_enter:
                    if(frame.scope_count==frame.scope_marks.count)throw std::logic_error("Scalar VM scope capacity exceeded");
                    scopes[frame.scope_marks.begin+frame.scope_count++]=static_cast<std::uint32_t>(frame.declared_count);break;
                case Opcode::scope_leave:leave_scope(frame);break;
                case Opcode::statement_enter:++frame.statement_depth;break;
                case Opcode::statement_leave:--frame.statement_depth;break;
                case Opcode::loop_enter:++frame.loop_depth;break;
                case Opcode::loop_leave:--frame.loop_depth;break;
                case Opcode::loop_next:
                    if(frame.broken){frame.broken=false;frame.pc=instruction.operand;}
                    else {frame.continued=false;frame.pc=instruction.target;}
                    break;
                case Opcode::set_break:
                    if(!frame.loop_depth)throw std::runtime_error("Break outside loop");
                    frame.broken=true;break;
                case Opcode::set_continue:
                    if(!frame.loop_depth)throw std::runtime_error("Continue outside loop");
                    frame.continued=true;break;
                case Opcode::call: {
                    const auto& operation=function.operations[instruction.operand];
                    const auto& target=program.targets[*operation.target];
                    if(target.kind==TargetKind::script) {
                        enter(target.function,operation.arguments.size(),
                            [&](std::size_t i)->const Value&{return read(frame,operation.arguments[i]);},
                            frames.top_id(),instruction.result,position,options,true);
                    } else {
                        // Resolve only after all preceding materialize/argument
                        // instructions have completed, preserving side effects.
                        if(!options.callbacks)throw std::runtime_error("Unknown function id: "+function_text(target.function_id));
                        WorkspaceVector<Value> snapshots(workspace);
                        snapshots.reserve(operation.arguments.size());
                        for(const auto argument:operation.arguments)snapshots.emplace_back(read(frame,argument));
                        const auto output=options.callbacks->invoke_external(target,snapshots.span(),position,2+frame.scope_count);
                        require_scalar_value(output);value(frame,instruction.result,output);
                    }
                    break;
                }
                case Opcode::set_return:
                    frame.result=read(frame,instruction.left);frame.returned=true;frame.return_location=frame.location;break;
                case Opcode::cleanup_enter:
                    regions.enter(frames.top_id(),function.cleanups[instruction.operand],
                        CleanupWatermark{{},frame.scope_count,0,0,frame.statement_depth,frame.loop_depth});break;
                case Opcode::cleanup_leave: {
                    const auto id=frames.top_id();regions.body_finished(id);
                    auto next=regions.unwound(id,frame);frame.pc=next.pc;
                    if(next.failure)throw std::logic_error("Scalar VM cleanup completed a body with a pending failure");
                    break;
                }
                case Opcode::cleanup_end: {
                    const auto id=frames.top_id();regions.finalizer_finished(id);
                    auto next=regions.unwound(id,frame);frame.pc=next.pc;
                    // The body failed and the finalizer completed: resume the
                    // first failure at its original innermost position.
                    if(next.failure){position=next.position;next.failure.rethrow();}
                    break;
                }
                case Opcode::function_end: {
                    frame.location=frame.return_location;position=position_of(frame);
                    auto output=vm::convert_return(frame.result,function.return_type);
                    const auto caller=frame.caller;const auto destination=frame.return_register;
                    cleanup(frame);frames.pop();
                    if(!caller)return output;
                    value(frames.resolve(caller),destination,std::move(output));break;
                }
                default:throw std::logic_error("Unsupported instruction reached the scalar VM");
                }
            }
            throw std::logic_error("Scalar VM invocation did not terminate");
        } catch(...) {
            bool recovered=false;
            // Recovery only moves already-reserved continuations; an internal
            // failure here would leave frames without a consistent owner.
            try{recovered=recover(mark,region_mark,position,options,resumed);}catch(...){std::terminate();}
            if(recovered)continue;
            unwind_frames(mark,options);regions.discard(region_mark);
            throw;
        }
    }
    void unwind_frames(std::size_t mark,const ExecutionOptions& options) noexcept {
        while(frames.size()>mark) {
            auto& frame=frames.top();
            if(options.callbacks)options.callbacks->note_script_error(position_of(frame));
            cleanup(frame);frames.pop();
        }
    }
    // Called inside the active catch block. Frames above the innermost active
    // region of this invocation root report their trace and release storage as
    // in a full unwind; the region's frame returns to its entry watermark.
    bool recover(std::size_t mark,std::size_t region_mark,ExecutionPosition& position,
            const ExecutionOptions& options,pending_error& resumed) {
        if(regions.size()<=region_mark)return false;
        const auto owner=regions.owner();
        while(frames.size()>mark&&frames.top_id()!=owner) {
            auto& frame=frames.top();
            if(options.callbacks)options.callbacks->note_script_error(position_of(frame));
            cleanup(frame);frames.pop();
        }
        if(frames.top_id()!=owner)throw std::logic_error("Scalar VM cleanup region lost its frame");
        auto& frame=frames.top();
        const auto failure=regions.failed(owner,frame,&position);
        while(frame.scope_count>failure.watermark.scopes)leave_scope(frame);
        frame.statement_depth=failure.watermark.statements;frame.loop_depth=failure.watermark.loops;
        auto next=regions.unwound(owner,frame);frame.pc=next.pc;
        // A failing finalizer resumes the body's first failure, if any, else
        // its own; either way at the original innermost position.
        if(next.kind==CleanupTransition::Kind::resume){position=next.position;resumed=std::move(next.failure);}
        return true;
    }
};

ScalarExecutor::ScalarExecutor(const ExecutableProgram& program,SlotPool& slots,WorkspaceBudget& workspace)
    :state_(std::make_unique<State>(program,slots,workspace)) {}
ScalarExecutor::~ScalarExecutor()=default;
Value ScalarExecutor::invoke(std::uint32_t function,std::span<const Value> arguments,
        std::span<const SlotId> globals,std::uint64_t& remaining_steps,ExecutionPosition* position,ExecutionOptions options) {
    ExecutionPosition local;
    return state_->invoke(function,arguments,globals,remaining_steps,position?*position:local,options);
}
}
