#include "execution.h"
#include "cleanup.h"
#include "../core/value_ops.h"
#include "session.h"
#include "../blocks.h"
#include "../core/statement.h"
#include "../heepalloc.h"
#include "../core/ids.h"
#include "../core/trace.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstdio>
#include <deque>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace azertian::vm {
std::shared_ptr<variable> ExecutionCallbacks::invoke_external_in(const CallTarget&,std::span<const Value>,
        const ExecutionPosition&,const std::shared_ptr<environment>&) {
    throw std::invalid_argument("Scalar VM object frames require an object host");
}
namespace {
constexpr std::array supported{
    Opcode::tick,Opcode::constant,Opcode::load_slot,Opcode::check_declaration,
    Opcode::declare_slot,Opcode::store,Opcode::snapshot,Opcode::materialize,
    Opcode::copy_reference,Opcode::truth,Opcode::unary,Opcode::binary,Opcode::convert,
    Opcode::jump,Opcode::branch_false,Opcode::branch_flow,Opcode::branch_returned,
    Opcode::scope_enter,Opcode::scope_leave,Opcode::statement_enter,Opcode::statement_leave,
    Opcode::loop_enter,Opcode::loop_next,Opcode::loop_leave,Opcode::set_break,
    Opcode::set_continue,Opcode::call,Opcode::set_return,Opcode::function_end,
    Opcode::cleanup_enter,Opcode::cleanup_leave,Opcode::cleanup_end,
    Opcode::member_get,Opcode::object_address,Opcode::object_register,Opcode::object_delete,
    Opcode::new_block,Opcode::block_address,Opcode::move_out,Opcode::drop,
    Opcode::check_method_receiver,Opcode::set_methods,Opcode::call_virtual
};

void require_scalar_value(const Value& value) {
    if(value.type_tag()==OBJECT_VALUE)
        throw std::invalid_argument("Scalar VM does not support object values");
}
bool object_signature(int return_type,const std::vector<int>& params) {
    return return_type==OBJECT_VALUE||std::find(params.begin(),params.end(),OBJECT_VALUE)!=params.end();
}
bool object_opcode(Opcode opcode) {
    switch(opcode) {
    case Opcode::member_get:case Opcode::object_address:case Opcode::object_register:
    case Opcode::object_delete:case Opcode::new_block:case Opcode::block_address:
    case Opcode::move_out:case Opcode::drop:case Opcode::check_method_receiver:
    case Opcode::set_methods:case Opcode::call_virtual:return true;
    default:return false;
    }
}
// Scalar frames refuse object arguments before each argument's type check.
template<class Argument> void validate_arguments(const FunctionRecord& function,
        std::size_t count,Argument argument,bool objects) {
    admission::check_arguments("Script function",function.function_id,function.param_types,count,
        [&](std::size_t i) -> std::optional<int> {
            const auto& value=argument(i);
            if(!objects)require_scalar_value(value);
            return value.type_tag();
        },true);
}
ExecutionPosition position_of(const FrameRecord& frame) noexcept {
    return {frame.function,frame.node,frame.location,frame.return_location,
        frame.pc==no_index?no_index:frame.pc-1};
}
std::shared_ptr<variable> wrap(const Value& value) {
    auto result=std::make_shared<variable>();value_access::assign(*result,value);return result;
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
    // A reference names a global SlotId, a scalar local cell, or (in object
    // frames) a tree-runtime variable: an object local, field or heap slot.
    struct RegisterValue {
        Value value;
        SlotId global;
        std::shared_ptr<variable> host;
        std::uint32_t cell=no_index;
        bool reference=false;
    };
    // Ownership records of one object frame. They are the tree runtime's own
    // structures, so blocks/heap ownership, temporaries and scope cleanup keep
    // their exact semantics. Locals live in frame->slots.
    struct Control {bool scope=false;statement::Mark temporaries;};
    struct ObjectFrame {
        std::shared_ptr<function_frame> frame;
        std::vector<std::shared_ptr<environment>> scopes; // [0] is the function root.
        std::vector<Control> controls;                    // Innermost last.
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
    // Stack-ordered with the object frames on FrameStack. A deque keeps the
    // records in place while reentrant roots push and pop above them.
    std::deque<ObjectFrame> object_frames;
    // A tree script call opens two entry scopes: entry clears the active trace,
    // success clears the published trace and failure publishes a copy. Nested
    // VM frames reproduce that; this flag is set whenever the thread's trace may
    // differ from cleared state (root entry, any failure, any native call), so
    // a clean script-to-script return touches no trace state.
    bool trace_touched=true;
    std::vector<bool> object_function;
    std::unordered_map<int,std::uint32_t> script_functions;
    std::vector<std::vector<std::shared_ptr<const method_table>>> method_tables;
    std::uint32_t free_cell=no_index;

    State(const ExecutableProgram& source,SlotPool& storage,WorkspaceBudget& budget)
        :program(source),pool(storage),workspace(budget),registers(budget),cells(budget,no_index),
         locals(budget),declared(budget),scopes(budget),frames(budget),layouts(budget),regions(budget) {
        verify_program(program);
        require_capabilities(program,scalar_capabilities());
        for(const auto& constant:program.constants)require_scalar_value(constant);
        const auto count=program.functions.size();
        object_function.assign(count,false);method_tables.resize(count);
        for(const auto& target:program.targets)
            if(target.kind==TargetKind::script)script_functions.emplace(target.function_id,target.function);
        // A function that uses objects, raw heap ownership or an internal
        // object entry runs as an object frame. So does every static caller:
        // ownership moves to the caller's scope (ro, mem_send_up) and mem_free
        // searches the caller chain, so those frames need scope records too.
        std::vector<std::vector<std::uint32_t>> callers(count);
        for(std::uint32_t index=0;index<count;++index) {
            const auto& function=program.functions[index];
            if(function.hidden_count||function.bound_context_count)
                throw std::invalid_argument("Scalar VM does not support generic or object function contexts");
            bool objects=(function.entry_kind&&!function.lifecycle)||object_signature(function.return_type,function.param_types);
            for(auto pc=function.begin;pc<function.end;++pc) {
                const auto& instruction=program.instructions[pc];
                if(object_opcode(instruction.opcode)||(instruction.opcode==Opcode::set_return&&instruction.operand!=0))objects=true;
                if(instruction.opcode==Opcode::object_register&&!function.operations[instruction.operand].contexts.empty())
                    throw std::invalid_argument("Scalar VM does not support generic object destructors");
                if(instruction.opcode==Opcode::set_methods)
                    for(const auto& method:function.operations[instruction.operand].methods)
                        if(!method.contexts.empty())throw std::invalid_argument("Scalar VM does not support generic virtual methods");
                if(instruction.opcode==Opcode::call) {
                    const auto& operation=function.operations[instruction.operand];
                    const auto& target=program.targets[*operation.target];
                    if(target.hidden_count||!operation.contexts.empty())
                        throw std::invalid_argument("Scalar VM does not support generic or object call contexts");
                    if(object_signature(target.return_type,target.param_types))objects=true;
                    if(target.kind==TargetKind::script) {
                        if(target.entry_kind)objects=true;
                        callers[target.function].push_back(index);
                    } else if(ids::is_heap_builtin(target.function_id))objects=true;
                }
            }
            object_function[index]=objects;
        }
        std::vector<std::uint32_t> work;
        for(std::uint32_t index=0;index<count;++index)if(object_function[index])work.push_back(index);
        while(!work.empty()) {
            const auto callee=work.back();work.pop_back();
            for(const auto caller:callers[callee])if(!object_function[caller]){object_function[caller]=true;work.push_back(caller);}
        }
        layouts.reserve(count);
        for(std::uint32_t index=0;index<count;++index) {
            auto layout=frame_layout(program,index);
            // Object frames keep locals in their tree-runtime function frame.
            if(object_function[index])layout.locals=0;
            layouts.emplace_back(layout);
            method_tables[index].resize(program.functions[index].operations.size());
        }
    }
    [[gnu::always_inline]] void release(std::uint32_t cell) noexcept {
        auto& binding=cells[cell];
        if(--binding.references)return;
        if(!pool.retire(binding.slot))std::terminate();
        binding.slot={};binding.next=free_cell;free_cell=cell;
    }
    // Invariant: a value register has an empty global, cell and host.
    void drop_reference(RegisterValue& reg) noexcept {
        if(reg.cell!=no_index)release(reg.cell);
        reg.host.reset();reg.global={};reg.cell=no_index;reg.reference=false;
    }
    void clear(RegisterValue& reg) noexcept {
        if(reg.reference)drop_reference(reg);
        reg.value=Value{};
    }
    const Value& read(const FrameRecord& frame,Register index) const {
        const auto& reg=registers[frame.registers.begin+index];
        if(!reg.reference)return reg.value;
        if(reg.cell!=no_index)return pool.resolve(cells[reg.cell].slot);
        if(reg.host)return value_access::read(*reg.host);
        return pool.resolve(reg.global);
    }
    void value(const FrameRecord& frame,Register destination,Value next) noexcept {
        auto& reg=registers[frame.registers.begin+destination];
        if(reg.reference)drop_reference(reg);
        reg.value=std::move(next);
    }
    [[gnu::always_inline]] void reference(const FrameRecord& frame,Register destination,SlotId global,std::uint32_t cell) noexcept {
        // Retain before clearing: copy_reference may overwrite its own input.
        if(cell!=no_index)++cells[cell].references;
        auto& reg=registers[frame.registers.begin+destination];clear(reg);
        reg.reference=true;reg.global=global;reg.cell=cell;
    }
    [[gnu::always_inline]] void reference(const FrameRecord& frame,Register destination,std::shared_ptr<variable> host) noexcept {
        auto& reg=registers[frame.registers.begin+destination];clear(reg);
        reg.reference=true;reg.host=std::move(host);
    }
    void copy(const FrameRecord& frame,Register destination,Register source) noexcept {
        const auto& reg=registers[frame.registers.begin+source];
        if(reg.reference&&reg.host)reference(frame,destination,std::shared_ptr<variable>(reg.host));
        else if(reg.reference)reference(frame,destination,reg.global,reg.cell);
        else value(frame,destination,reg.value);
    }
    // The tree-runtime view of an operand: a referenced variable keeps its
    // identity (store compares storage), anything else becomes a fresh handle.
    std::shared_ptr<variable> variable_of(const FrameRecord& frame,Register index) const {
        const auto& reg=registers[frame.registers.begin+index];
        if(reg.reference&&reg.host)return reg.host;
        return wrap(read(frame,index));
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
    ObjectFrame& objects(const FrameRecord& frame) {return object_frames[frame.object];}
    const std::shared_ptr<environment>& scope_of(const FrameRecord& frame) {return objects(frame).scopes.back();}
    static ObjectHost& host_of(const ExecutionOptions& options) {
        auto* host=options.callbacks?options.callbacks->objects():nullptr;
        if(!host)throw std::invalid_argument("Scalar VM object frames require a script object host");
        return *host;
    }
    // Brackets tree-runtime helpers that may run destructors (script code).
    template<class Body> void runtime(const ExecutionOptions& options,Body body) {
        auto& host=host_of(options);const auto mark=host.enter_runtime();
        trace_touched=true;
        try{body();}catch(...){try{host.leave_runtime(mark);}catch(...){}throw;}
        host.leave_runtime(mark);
    }
    void check_declaration(const FrameRecord& frame,int index,const FunctionRecord& function) {
        if(frame.object!=no_index) {
            const auto& slots=objects(frame).frame->slots;
            if(index<0||static_cast<std::size_t>(index)<function.param_types.size()||static_cast<std::size_t>(index)>=slots.size())
                throw std::runtime_error("Invalid local variable declaration: "+std::to_string(index));
            if(slots[static_cast<std::size_t>(index)])throw std::runtime_error("Duplicate variable slot: "+std::to_string(index));
            return;
        }
        if(index<0||static_cast<std::size_t>(index)<function.param_types.size()||
                static_cast<std::size_t>(index)>=frame.locals.count)
            throw std::runtime_error("Invalid local variable declaration: "+std::to_string(index));
        if(locals[frame.locals.begin+static_cast<std::size_t>(index)]!=no_index)
            throw std::runtime_error("Duplicate variable slot: "+std::to_string(index));
    }
    const FunctionRecord& function_of(const FrameRecord& frame) const noexcept {return program.functions[frame.function];}
    SlotId global_slot(int index,const FrameRecord& frame,std::span<const SlotId> globals) const {
        const auto& function=function_of(frame);
        const auto relative=static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1);
        if(relative>=function.global_count||function.global_offset>globals.size()||
                relative>=globals.size()-function.global_offset)
            throw std::runtime_error("Global variable slot is outside its module: "+std::to_string(index));
        return globals[function.global_offset+static_cast<std::size_t>(relative)];
    }
    std::shared_ptr<variable> object_local(const FrameRecord& frame,int index) {
        const auto& slots=objects(frame).frame->slots;
        if(static_cast<std::size_t>(index)>=slots.size())
            throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
        auto result=slots[static_cast<std::size_t>(index)];
        if(!result)throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
        return result;
    }
    [[gnu::always_inline]] void load(const FrameRecord& frame,Register destination,int index,std::span<const SlotId> globals) {
        if(index<0) {reference(frame,destination,global_slot(index,frame,globals),no_index);return;}
        if(frame.object!=no_index){reference(frame,destination,object_local(frame,index));return;}
        if(static_cast<std::size_t>(index)>=frame.locals.count)
            throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
        const auto cell=local(frame,static_cast<std::size_t>(index));
        if(cell==no_index)
            throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
        reference(frame,destination,{},cell);
    }
    void store(const FrameRecord& frame,Register destination,Register target,Register source,const ExecutionOptions& options) {
        auto& reg=registers[frame.registers.begin+target];
        if(!reg.reference)throw std::logic_error("Scalar VM store target is not a slot reference");
        if(reg.host) {
            // Assignment can copy, replace or finalize objects (destructors).
            auto storage=reg.host;auto value=variable_of(frame,source);
            if(value->type_tag()==OBJECT_VALUE||storage->type_tag()==OBJECT_VALUE)
                runtime(options,[&]{blocks::store(storage,value,scope_of(frame));});
            else blocks::store(storage,value,scope_of(frame));
            this->value(frame,destination,value_access::read(*storage));
            // The operands end with the assignment, value first, as the tree's
            // do: a target whose container is already freed abandons the
            // stored object here, so the result reads as expired. Lowering
            // never reads a store's operand registers again.
            clear(registers[frame.registers.begin+source]);clear(reg);
            return;
        }
        Value next=read(frame,source);
        if(next.type_tag()==OBJECT_VALUE)
            throw std::invalid_argument("Scalar VM global and scalar local storage cannot hold an object value");
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
    // Called inside an active catch block while an error unwinds an object
    // frame: statement temporaries and scopes above mark end innermost first,
    // and the root scope too when the frame is leaving. Cleanup errors after
    // the in-flight one are discarded. As each tree statement_scope and
    // with_cleanup level does, every level rethrows the first error after its
    // cleanup, republishing its trace before the next level's destructors run.
    void unwind_objects(FrameRecord& frame,std::size_t mark,bool whole,const ExecutionOptions& options) noexcept {
        auto* host=options.callbacks?options.callbacks->objects():nullptr;
        auto& record=objects(frame);
        pending_error failure;failure.capture_current();
        const auto runtime_mark=host?host->enter_runtime():0;
        const auto level_ended=[&]() noexcept {
            try{failure.rethrow();}catch(...){failure=pending_error{};failure.capture_current();}
        };
        while(record.controls.size()>mark) {
            const auto control=record.controls.back();record.controls.pop_back();
            try {
                if(control.scope) {
                    auto scope=std::move(record.scopes.back());record.scopes.pop_back();
                    scope->cleanup(scope);
                } else statement::finish(control.temporaries,record.scopes.back()->site());
            } catch(...) {failure.capture_current();}
            level_ended();
        }
        if(whole)try{auto root=record.scopes.front();root->cleanup(root);}catch(...){failure.capture_current();}
        if(host)try{host->leave_runtime(runtime_mark);}catch(...){failure.capture_current();}
        try{failure.rethrow();}catch(...){}
    }
    void cleanup(FrameRecord& frame) noexcept {
        for(std::size_t i=0;i<frame.registers.count;++i)clear(registers[frame.registers.begin+i]);
        for(std::size_t i=0;i<frame.locals.count;++i)if(local(frame,i)!=no_index)release(local(frame,i));
        registers.truncate(frame.registers.begin);locals.truncate(frame.locals.begin);
        declared.truncate(frame.declared.begin);scopes.truncate(frame.scope_marks.begin);
        if(frame.object!=no_index) {
            // Normal and error exits have already run every scope cleanup.
            if(frame.object+1!=object_frames.size())std::terminate();
            object_frames.pop_back();frame.object=no_index;
        }
    }
    ownership::Site calling_scope(FrameId caller,const ExecutionOptions& options) {
        if(!caller)return options.caller;
        auto& record=frames.resolve(caller);
        return record.object==no_index?ownership::Site{}:scope_of(record)->site();
    }
    // Object frames are entered out of line, which keeps the scalar
    // dispatch loop's inlining independent of the object model.
    template<class Argument> [[gnu::noinline]] void enter_object_frame(std::uint32_t index,std::size_t count,Argument& argument,
            FrameRecord& frame,const FrameLayout& layout,const ExecutionOptions& options) {
        const auto& function=program.functions[index];
        // Same order as the tree executor: parameters are stored from
        // the calling scope, then bound to the new function root.
        auto& host=host_of(options);
        const auto calling=calling_scope(frame.caller,options);
        if(!calling.record)throw std::logic_error("Scalar VM object frame has no calling scope");
        const auto owner=host.owner();
        ObjectFrame record;record.frame=std::make_shared<function_frame>(host.ownership_arena());
        auto& tree=*record.frame;
        tree.global_offset=function.global_offset;tree.global_count=function.global_count;tree.param_count=count;
        tree.slots.resize(function.param_types.size()+static_cast<std::size_t>(function.local_count));
        auto root=std::make_shared<environment>(host.ownership_arena(),owner->baseEnv,calling.scope(),record.frame,true);
        root->script=owner;
        record.scopes.push_back(root);
        for(std::size_t i=0;i<count;++i) {
            auto storage=std::make_shared<variable>();auto value=wrap(argument(i));
            if(value->type_tag()==OBJECT_VALUE)runtime(options,[&]{blocks::store(storage,value,calling);});
            else blocks::store(storage,value,calling);
            statement::bind_parameter(storage,i,root);
        }
        registers.append_default(layout.registers);scopes.append_default(layout.scope_marks);
        object_frames.push_back(std::move(record));frame.object=static_cast<std::uint32_t>(object_frames.size()-1);
        try{frames.push(std::move(frame));}catch(...){cleanup(frame);throw;}
    }
    template<class Argument> void enter(std::uint32_t index,std::size_t count,Argument argument,
            FrameId caller,Register destination,ExecutionPosition& position,const ExecutionOptions& options,bool admit) {
        const ExecutionPosition entry{index,0,0,0,no_index};
        try {
            if(caller&&trace_touched)trace::discard_active();
            FrameRecord frame;frame.function=index;frame.caller=caller;frame.return_register=destination;
            if(admit&&options.calls)frame.admission=options.calls->enter();
            const auto& function=program.functions[index];
            const bool objects=object_function[index];
            validate_arguments(function,count,argument,objects);
            const auto layout=layouts[index];
            frame.pc=function.begin;frame.end=function.end;
            frame.registers={registers.size(),layout.registers};frame.locals={locals.size(),layout.locals};
            frame.declared={declared.size(),layout.locals};frame.scope_marks={scopes.size(),layout.scope_marks};
            // Preflight every backing array before publishing any live range.
            // Argument readers use offsets, so relocating caller registers is safe.
            registers.prepare_append(layout.registers);locals.prepare_append(layout.locals);
            declared.prepare_append(layout.locals);scopes.prepare_append(layout.scope_marks);
            regions.prepare(layout.cleanups);
            if(objects){enter_object_frame(index,count,argument,frame,layout,options);return;}
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
            if(caller){trace_touched=true;trace::publish_current();}
            throw;
        }
    }
    std::shared_ptr<const method_table> methods(std::uint32_t function,std::uint32_t operand) {
        auto& cached=method_tables[function][operand];
        if(!cached) {
            const auto& operation=program.functions[function].operations[operand];
            auto table=std::make_shared<method_table>();table->reserve(operation.methods.size());
            for(const auto& method:operation.methods)table->push_back({program.targets[method.target].function_id,{}});
            cached=std::move(table);
        }
        return cached;
    }
    static address pointer(const Value& value,const char* message) {
        if(value.type_tag()!=ADDRESS_VALUE)throw std::runtime_error(message);
        return value.get<address>();
    }
    Value invoke(std::uint32_t index,std::span<const Value> arguments,std::span<const SlotId> globals,
            std::uint64_t& steps,ExecutionPosition& position,ExecutionOptions options) {
        if(index>=program.functions.size())throw std::out_of_range("Scalar VM function index is outside its program");
        position={index,0,0,0,no_index};trace_touched=true;
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
                if(frame.pc>=frame.end)throw std::logic_error("Scalar VM function did not terminate");
                const auto& instruction=program.instructions[frame.pc];
                const auto& source=program.sources[frame.pc];
                frame.node=source.node;frame.location=source.location;++frame.pc;
                position=position_of(frame);
                switch(instruction.opcode) {
                case Opcode::tick:
                    if(!steps)throw std::runtime_error("Script execution step limit exceeded");
                    --steps;break;
                case Opcode::constant:value(frame,instruction.result,program.constants[instruction.operand]);break;
                case Opcode::load_slot:load(frame,instruction.result,std::bit_cast<int>(instruction.operand),globals);break;
                case Opcode::check_declaration:check_declaration(frame,std::bit_cast<int>(instruction.operand),function_of(frame));break;
                case Opcode::declare_slot: {
                    const auto slot=instruction.operand;
                    if(frame.object!=no_index) {
                        auto storage=std::make_shared<variable>();
                        const auto& scope=scope_of(frame);
                        if(instruction.left!=no_index) {
                            auto initial=variable_of(frame,instruction.left);
                            if(initial->type_tag()==OBJECT_VALUE)runtime(options,[&]{blocks::store(storage,initial,scope);});
                            else blocks::store(storage,initial,scope);
                        }
                        // A hand-written initializer may have declared this
                        // slot meanwhile; the tree executor replaces it too.
                        statement::declare_local(storage,slot,scope);
                        break;
                    }
                    // A hand-written initializer may declare this slot after
                    // the outer declaration's check; replace its storage then.
                    Value initial=instruction.left==no_index?Value{}:read(frame,instruction.left);
                    const auto cell=allocate(std::move(initial));
                    if(local(frame,slot)!=no_index)release(local(frame,slot));
                    else declared[frame.declared.begin+frame.declared_count++]=slot;
                    local(frame,slot)=cell;break;
                }
                case Opcode::store:store(frame,instruction.result,instruction.left,instruction.right,options);break;
                case Opcode::snapshot:value(frame,instruction.result,read(frame,instruction.left));break;
                case Opcode::materialize: {
                    // Arguments snapshot an owned object before the next one
                    // can change it; scalars are plain value copies.
                    const auto& current=read(frame,instruction.left);
                    if(frame.object==no_index||current.type_tag()!=OBJECT_VALUE){value(frame,instruction.result,current);break;}
                    auto source=variable_of(frame,instruction.left);std::shared_ptr<variable> result;
                    runtime(options,[&]{result=blocks::materialize(source,scope_of(frame));});
                    value(frame,instruction.result,value_access::read(*result));break;
                }
                case Opcode::copy_reference:copy(frame,instruction.result,instruction.left);break;
                case Opcode::truth:value(frame,instruction.result,Value(value_ops::truth(read(frame,instruction.left))));break;
                case Opcode::unary:value(frame,instruction.result,value_ops::unary(static_cast<UnaryOperation>(instruction.operand),read(frame,instruction.left)));break;
                case Opcode::binary:value(frame,instruction.result,value_ops::binary(static_cast<BinaryOperation>(instruction.operand),read(frame,instruction.left),read(frame,instruction.right)));break;
                case Opcode::convert:value(frame,instruction.result,value_ops::convert(read(frame,instruction.left),static_cast<int>(instruction.operand)));break;
                case Opcode::jump:frame.pc=instruction.target;break;
                case Opcode::branch_false:if(!value_ops::truth(read(frame,instruction.left)))frame.pc=instruction.target;break;
                case Opcode::branch_flow:if(frame.returned||frame.broken||frame.continued)frame.pc=instruction.target;break;
                case Opcode::branch_returned:if(frame.returned)frame.pc=instruction.target;break;
                case Opcode::scope_enter:
                    if(frame.scope_count==frame.scope_marks.count)throw std::logic_error("Scalar VM scope capacity exceeded");
                    if(frame.object!=no_index) {
                        auto& record=objects(frame);
                        record.controls.reserve(record.controls.size()+1);
                        auto scope=std::make_shared<environment>(host_of(options).ownership_arena(),
                                                                 record.scopes.back(),ownership::ScopeId{},record.frame);
                        scope->script=record.scopes.back()->script;
                        record.scopes.push_back(std::move(scope));record.controls.push_back({true,{}});
                    }
                    scopes[frame.scope_marks.begin+frame.scope_count++]=static_cast<std::uint32_t>(frame.declared_count);break;
                case Opcode::scope_leave:
                    leave_scope(frame);
                    if(frame.object!=no_index) {
                        auto& record=objects(frame);
                        if(record.controls.empty()||!record.controls.back().scope)throw std::logic_error("Scalar VM object scope records are unbalanced");
                        record.controls.pop_back();
                        auto scope=std::move(record.scopes.back());record.scopes.pop_back();
                        runtime(options,[&]{scope->cleanup(scope);});
                    }
                    break;
                case Opcode::statement_enter:
                    ++frame.statement_depth;
                    if(frame.object!=no_index){auto& record=objects(frame);record.controls.push_back({false,statement::begin(record.frame->temporary_id(),record.frame->temporaries.size())});}
                    break;
                case Opcode::statement_leave:
                    --frame.statement_depth;
                    if(frame.object!=no_index) {
                        auto& record=objects(frame);
                        if(record.controls.empty()||record.controls.back().scope)throw std::logic_error("Scalar VM object statement records are unbalanced");
                        const auto mark=record.controls.back().temporaries;record.controls.pop_back();
                        if(record.frame->temporaries.size()>mark.count)
                            runtime(options,[&]{statement::finish(mark,record.scopes.back()->site());});
                    }
                    break;
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
                    const auto& operation=function_of(frame).operations[instruction.operand];
                    const auto& target=program.targets[*operation.target];
                    if(target.kind==TargetKind::script) {
                        enter(target.function,operation.arguments.size(),
                            [&](std::size_t i)->const Value&{return read(frame,operation.arguments[i]);},
                            frames.top_id(),instruction.result,position,options,true);
                    } else {
                        // Resolve only after all preceding materialize/argument
                        // instructions have completed, preserving side effects.
                        if(!options.callbacks)throw std::runtime_error("Unknown function id: "+function_id_text(target.function_id));
                        WorkspaceVector<Value> snapshots(workspace);
                        snapshots.reserve(operation.arguments.size());
                        for(const auto argument:operation.arguments)snapshots.emplace_back(read(frame,argument));
                        if(frame.object!=no_index) {
                            // The result is the returned variable itself, as in
                            // the tree executor; mem_get yields a heap slot.
                            const auto scope=scope_of(frame);
                            trace_touched=true;
                            auto output=options.callbacks->invoke_external_in(target,snapshots.span(),position,scope);
                            blocks::adopt_result(output,scope);
                            reference(frame,instruction.result,std::move(output));
                            break;
                        }
                        trace_touched=true;
                        const auto output=options.callbacks->invoke_external(target,snapshots.span(),position,2+frame.scope_count);
                        require_scalar_value(output);value(frame,instruction.result,output);
                    }
                    break;
                }
                case Opcode::set_return: {
                    if(frame.object==no_index) {
                        frame.result=read(frame,instruction.left);frame.returned=true;frame.return_location=frame.location;break;
                    }
                    // Object results leave the frame now: a local moves to the
                    // caller, anything else is copied into its temporaries.
                    const auto& scope=scope_of(frame);
                    auto source=variable_of(frame,instruction.left);Value result;
                    if(source->type_tag()==OBJECT_VALUE) {
                        std::shared_ptr<variable> exported;
                        runtime(options,[&]{exported=blocks::export_result(source,scope);});
                        result=value_access::read(*exported);
                    } else {
                        result=value_access::read(*source);
                        if(instruction.operand) {
                            if(result.type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object return requires address or object type");
                            heap::return_object(result.get<address>(),scope);
                        }
                    }
                    frame.result=std::move(result);frame.returned=true;frame.return_location=frame.location;break;
                }
                case Opcode::cleanup_enter:
                    regions.enter(frames.top_id(),function_of(frame).cleanups[instruction.operand],
                        CleanupWatermark{{},frame.scope_count,0,0,frame.statement_depth,frame.loop_depth,
                            frame.object==no_index?0:objects(frame).controls.size()});break;
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
                case Opcode::new_block: {
                    auto block=blocks::create(static_cast<std::size_t>(std::bit_cast<int>(instruction.operand)),host_of(options).owner());
                    blocks::adopt_temporary(block,objects(frame).frame.get());
                    value(frame,instruction.result,Value(std::move(block)));break;
                }
                case Opcode::block_address: {
                    const auto block=read(frame,instruction.left).object_handle();
                    if(!block||block->buffer)throw std::runtime_error("Object address requires a class value");
                    value(frame,instruction.result,Value(blocks::address_of(block)));break;
                }
                case Opcode::object_address: {
                    const auto object=pointer(read(frame,instruction.left),"Object pointer requires address type");
                    value(frame,instruction.result,Value(heap::object_address(object,std::bit_cast<int>(instruction.operand))));break;
                }
                case Opcode::member_get:
                    reference(frame,instruction.result,heap::getAt(pointer(read(frame,instruction.left),"Object pointer requires address type")));break;
                case Opcode::object_register: {
                    const auto object=pointer(read(frame,instruction.left),"Object pointer requires address type");
                    const auto& operation=function_of(frame).operations[instruction.operand];
                    std::optional<int> destructor;
                    if(operation.target)destructor=program.targets[*operation.target].function_id;
                    heap::register_object(object,destructor,operation.manual,scope_of(frame));
                    value(frame,instruction.result,Value{});break;
                }
                case Opcode::object_delete: {
                    const auto object=pointer(read(frame,instruction.left),"Object pointer requires address type");
                    runtime(options,[&]{heap::delete_object(object,scope_of(frame));});
                    value(frame,instruction.result,Value{});break;
                }
                case Opcode::move_out: {
                    const auto slot=std::bit_cast<int>(instruction.operand);
                    if(slot<0){value(frame,instruction.result,pool.resolve(global_slot(slot,frame,globals)));break;}
                    auto moved=blocks::move_out(object_local(frame,slot),scope_of(frame));
                    value(frame,instruction.result,value_access::read(*moved));break;
                }
                case Opcode::drop: {
                    // Only object storage can own a value; globals never do.
                    auto& target=registers[frame.registers.begin+instruction.left];
                    if(target.reference&&target.host) {
                        auto storage=target.host;
                        runtime(options,[&]{blocks::drop(storage,scope_of(frame));});
                    }
                    value(frame,instruction.result,Value{});break;
                }
                case Opcode::check_method_receiver:
                    pointer(read(frame,instruction.left),"Virtual method receiver must be address");break;
                case Opcode::set_methods:
                    heap::set_methods(pointer(read(frame,instruction.left),"Virtual method receiver must be address"),
                        methods(frame.function,instruction.operand),host_of(options).owner());
                    value(frame,instruction.result,Value{});break;
                case Opcode::call_virtual: {
                    const auto& operation=function_of(frame).operations[instruction.operand];
                    if(operation.arguments.empty())throw std::runtime_error("Virtual method receiver must be address");
                    const auto receiver=pointer(read(frame,operation.arguments[0]),"Virtual method receiver must be address");
                    const auto table=heap::methods_at(receiver,host_of(options).owner());
                    const auto slot=static_cast<std::size_t>(static_cast<std::uint32_t>(operation.immediate));
                    if(slot>=table->size())throw std::runtime_error("Virtual method slot is out of range");
                    const auto& method=(*table)[slot];
                    if(!method.contexts.empty())throw std::invalid_argument("Scalar VM does not support generic virtual methods");
                    const auto found=script_functions.find(method.function_id);
                    if(found==script_functions.end())throw std::runtime_error("Unknown function id: "+function_id_text(method.function_id));
                    enter(found->second,operation.arguments.size(),
                        [&](std::size_t i)->const Value&{return read(frame,operation.arguments[i]);},
                        frames.top_id(),instruction.result,position,options,true);
                    auto& callee=frames.top();callee.check_return=true;callee.checked_return_type=operation.return_type;
                    break;
                }
                case Opcode::function_end: {
                    frame.location=frame.return_location;position=position_of(frame);
                    auto output=value_ops::convert_return(frame.result,function_of(frame).return_type);
                    if(frame.object!=no_index) {
                        // The root scope ends after return conversion, as the
                        // tree executor's with_cleanup does.
                        const auto root=objects(frame).scopes.front();
                        runtime(options,[&]{root->cleanup(root);});
                    }
                    const auto caller=frame.caller;const auto destination=frame.return_register;
                    const bool check=frame.check_return;const int expected=frame.checked_return_type;
                    cleanup(frame);frames.pop();
                    if(!caller)return output;
                    if(trace_touched){clear_error_trace();trace_touched=false;}
                    auto& resumed_frame=frames.resolve(caller);
                    if(resumed_frame.object!=no_index&&output.type_tag()==OBJECT_VALUE)
                        blocks::adopt_result(wrap(output),scope_of(resumed_frame));
                    const auto got=output.type_tag();
                    value(resumed_frame,destination,std::move(output));
                    if(check&&expected!=ANY_VALUE&&got!=expected)
                        throw std::runtime_error(std::string("Virtual method return type mismatch: expected ")+type_name(expected)+", got "+type_name(got));
                    break;
                }
                default:throw std::logic_error("Unsupported instruction reached the scalar VM");
                }
            }
            throw std::logic_error("Scalar VM invocation did not terminate");
        } catch(...) {
            trace_touched=true;
            bool recovered=false;
            // Recovery only moves already-reserved continuations; an internal
            // failure here would leave frames without a consistent owner.
            try{recovered=recover(mark,region_mark,position,options,resumed);}catch(...){std::terminate();}
            if(recovered)continue;
            unwind_frames(mark,options);regions.discard(region_mark);
            throw;
        }
    }
    // A frame leaving with an error first ends its object scopes (which may
    // run destructors), then reports its trace position, as invoke_bound does.
    void unwind_frame(const ExecutionOptions& options) noexcept {
        auto& frame=frames.top();
        if(frame.object!=no_index)unwind_objects(frame,0,true,options);
        if(options.callbacks)options.callbacks->note_script_error(position_of(frame));
        // The root frame's entry scopes are real; nested frames publish here.
        if(frame.caller)trace::publish_current();
        cleanup(frame);frames.pop();
    }
    void unwind_frames(std::size_t mark,const ExecutionOptions& options) noexcept {
        while(frames.size()>mark)unwind_frame(options);
    }
    // Called inside the active catch block. Frames above the innermost active
    // region of this invocation root report their trace and release storage as
    // in a full unwind; the region's frame returns to its entry watermark.
    bool recover(std::size_t mark,std::size_t region_mark,ExecutionPosition& position,
            const ExecutionOptions& options,pending_error& resumed) {
        if(regions.size()<=region_mark)return false;
        const auto owner=regions.owner();
        while(frames.size()>mark&&frames.top_id()!=owner)unwind_frame(options);
        if(frames.top_id()!=owner)throw std::logic_error("Scalar VM cleanup region lost its frame");
        auto& frame=frames.top();
        const auto failure=regions.failed(owner,frame,&position);
        // The region now holds the first error; scopes and statements inside
        // its body end before the finalizer runs.
        if(frame.object!=no_index)unwind_objects(frame,failure.watermark.controls,false,options);
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
