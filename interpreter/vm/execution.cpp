#include "execution.h"
#include "cleanup.h"
#include "../core/value_ops.h"
#include "session.h"
#include "../blocks.h"
#include "../core/statement.h"
#include "../core/lifecycle.h"
#include "../heepalloc.h"
#include "../core/ids.h"
#include "../core/trace.h"
#include "../jit/tier.h"
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
    Opcode::check_method_receiver,Opcode::set_methods,Opcode::call_virtual,
    Opcode::bind_context,Opcode::context_abi,Opcode::context_default,Opcode::return_typed,
    Opcode::check_type,Opcode::check_object,Opcode::buffer_new,Opcode::buffer_operation,Opcode::value_compare
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
    // Ownership records of one object frame. Locals and temporaries live in
    // the function frame, which the host edge shares; each lexical scope is a
    // core ownership record with the local slots it declared, in declaration
    // order (a hand-written exec can declare a slot twice). Members end in
    // order: the scopes' leftovers, then the frame's temporaries and locals.
    struct Control {bool scope=false;statement::Mark temporaries;};
    struct ObjectScope {
        ownership::Scope ownership;
        std::vector<std::size_t> declared;
        ObjectScope(std::shared_ptr<ownership::Arena> arena,ownership::Scope::Links links):ownership(std::move(arena),links) {}
    };
    // A closing scope's locals, as the tree's environment::cleanup sees them.
    class ObjectLocals final:public lifecycle::ScopeLocals {
        function_frame& frame_;ObjectScope& scope_;bool root_;
    public:
        ObjectLocals(function_frame& frame,ObjectScope& scope,bool root) noexcept:frame_(frame),scope_(scope),root_(root) {}
        std::vector<std::shared_ptr<variable>> leftovers() const override {
            std::vector<std::shared_ptr<variable>> result;
            if(root_)for(std::size_t i=0;i<frame_.param_count&&i<frame_.slots.size();++i)result.push_back(frame_.slots[i]);
            for(auto index:scope_.declared)result.push_back(frame_.slots[index]);
            return result;
        }
        void undeclare() noexcept override {
            for(auto index:scope_.declared)frame_.slots[index].reset();
            scope_.declared.clear();
        }
    };
    // A scope or statement cleanup the dispatch loop drives. Destructors of
    // this script run as continuation frames above the waiting frame; others
    // are dispatched natively.
    enum class Tail : std::uint8_t {none,scope_closed,statement_done,unwinding,dropped,rollback};
    struct Pending {
        Tail tail=Tail::none;
        std::optional<ObjectScope> closing;              // a popped scope being closed
        std::optional<ObjectLocals> locals;
        std::optional<lifecycle::ScopeFinalizer> scope;
        std::optional<lifecycle::TemporariesFinalizer> temporaries;
        std::optional<lifecycle::ReleaseFinalizer> release;  // drop
        std::optional<lifecycle::HeapDestroyer> heap;        // delete
        std::shared_ptr<variable> storage;                   // a drop's storage, cleared at the end
        // A failed copy's partial copies, ended innermost first in their
        // contexts and sharing the copy's error, as the copy's catches did.
        std::vector<std::pair<std::shared_ptr<slot_block>,ownership::Site>> rollback;
        std::size_t next=0;
        pending_error failure;
        std::optional<lifecycle::BlockFinalizer> block;
        lifecycle::DestructorCall call{};
        ownership::Site site;                            // the dispatch caller
        // The continuation frame of a yielded call and its two entries.
        bool waiting=false,reported=false;
        FrameId callee;
        std::size_t region_mark=0;
        trace::open_entry dispatch,function;
        bool active() const noexcept{return scope||temporaries||release||heap||block;}
        // The driver other than a rollback's block traversal.
        template<class Step> decltype(auto) step(Step step) {
            if(scope)return step(*scope);
            if(temporaries)return step(*temporaries);
            if(release)return step(*release);
            return step(*heap);
        }
    };
    // An error leaving an object frame ends its statement and scope levels
    // one at a time, as the tree's statement and scope levels do: each level
    // ends (later errors discarded) and republishes the first error before the
    // next one starts. A region of the frame recovers afterwards; otherwise
    // the root scope ends too and the frame leaves.
    struct Unwind {
        enum class Mode : std::uint8_t {none,levels,root,finished} mode=Mode::none;
        std::size_t target=0;            // controls watermark
        bool region=false,republished=false;
        pending_error failure;           // the error in flight, captured first
        ExecutionPosition position;      // its innermost position
        CleanupTransition transition;    // the region's capture
    };
    // A frame's cleanup in progress. Pooled: an object frame borrows one
    // only while a cleanup or an unwind is under way.
    struct Cleanup {Pending pending;Unwind unwind;};
    struct ObjectFrame {
        std::shared_ptr<function_frame> frame;
        std::vector<ObjectScope> scopes; // [0] is the function root.
        std::vector<Control> controls;   // Innermost last.
        Cleanup* cleanup=nullptr;
        type_contexts bound;             // bind_context results
    };
    const ExecutableProgram& program;
    SlotPool& pool;
    WorkspaceBudget& workspace;
    WorkspaceVector<RegisterValue> registers;
    WorkspaceVector<Cell> cells;
    WorkspaceVector<std::uint32_t> locals,declared,scopes;
    FrameStack frames;
    WorkspaceVector<FrameLayout> layouts;
    struct BudgetBatch { ProgramCounter end=0; std::uint32_t ticks=0; };
    WorkspaceVector<BudgetBatch> budget_batches;
    // Compiler cleanup continuations for every active region of every frame,
    // innermost last. Each frame reserves its own bound before publication,
    // so entering a region and recovering from a failure never allocate.
    CompilerCleanupStack regions;
    // Stack-ordered with the object frames on FrameStack. A deque keeps the
    // records in place while reentrant roots push and pop above them.
    std::deque<ObjectFrame> object_frames;
    std::deque<Cleanup> cleanups;
    std::vector<Cleanup*> free_cleanups;
    blocks::copy_rollback partial_copies;  // with_rollback's, kept for its capacity
    type_contexts bound_call;              // call_contexts'
    // A frame whose pending cleanup resumes at the next loop start, after
    // its continuation failed inside the dispatch catch.
    FrameId drive_pending;
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
    // Frames consult the tier only when they reach their bound.
    jit::Tier tier;

    State(const ExecutableProgram& source,SlotPool& storage,WorkspaceBudget& budget,const jit::Config& tier_config)
        :program(source),pool(storage),workspace(budget),registers(budget),cells(budget,no_index),
         locals(budget),declared(budget),scopes(budget),frames(budget),layouts(budget),budget_batches(budget),regions(budget),
         tier(source,tier_config) {
        verify_program(program);
        require_capabilities(program,scalar_capabilities());
        budget_batches.append_default(program.instructions.size());
        // Each segment is straight-line, has no callback/cleanup/control edge,
        // and contains at most 64 budget points and 256 instructions. Regions
        // are derived from verified instructions, never trusted input metadata.
        for (const auto& function : program.functions) {
            for (auto begin=function.begin; begin<function.end;) {
                if(program.instructions[begin].opcode!=Opcode::tick){++begin;continue;}
                auto end=begin;std::uint32_t ticks=0;
                while(end<function.end && end-begin<256 && batch_opcode(program.instructions[end].opcode)) {
                    if(program.instructions[end].opcode==Opcode::tick) {if(ticks==64)break;++ticks;}
                    ++end;
                }
                if(ticks>1)budget_batches[begin]={end,ticks};
                begin=std::max(begin+1,end);
            }
        }
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
            // Generic functions keep their hidden and bound contexts in an
            // object frame.
            bool objects=(function.entry_kind&&!function.lifecycle)||object_signature(function.return_type,function.param_types)||
                function.hidden_count||function.bound_context_count;
            for(auto pc=function.begin;pc<function.end;++pc) {
                const auto& instruction=program.instructions[pc];
                if(object_opcode(instruction.opcode)||(instruction.opcode==Opcode::set_return&&instruction.operand!=0))objects=true;
                if(instruction.opcode==Opcode::call) {
                    const auto& operation=function.operations[instruction.operand];
                    const auto& target=program.targets[*operation.target];
                    if(target.kind!=TargetKind::script&&(target.hidden_count||!operation.contexts.empty()))
                        throw std::invalid_argument("Scalar VM does not support generic or object call contexts");
                    if(object_signature(target.return_type,target.param_types))objects=true;
                    if(target.kind==TargetKind::script) {
                        if(target.entry_kind)objects=true;
                        callers[target.function].push_back(index);
                    } else if(ids::is_heap_builtin(target.function_id)||
                            target.function_id==ids::id(ids::builtin::reflect_invoke_function)) {
                        // Heap builtins act on the caller's scope; reflection calls
                        // script code from it, whose returned objects then belong
                        // to that scope.
                        objects=true;
                    }
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
        tier.classify(object_function);
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
    static ownership::Site scope_site(ObjectFrame& record,ObjectScope& scope) noexcept {
        return {&scope.ownership.record(),&record.frame->temporary_record()};
    }
    ownership::Site site(const FrameRecord& frame) {auto& record=objects(frame);return scope_site(record,record.scopes.back());}
    // Ends a scope as environment::cleanup does: once, and without a driver
    // when nothing can run or be released.
    // True when ending the scope could run no code and release nothing: it
    // then only closes and undeclares its locals.
    static bool idle(const function_frame& frame,const ObjectScope& scope,bool root) noexcept {
        const auto owning=[&](std::size_t index){const auto& slot=frame.slots[index];return slot&&blocks::owns(*slot);};
        if(!scope.ownership.record().owned.empty()||(root&&!frame.temporaries.empty()))return false;
        if(root)for(std::size_t i=0;i<frame.param_count&&i<frame.slots.size();++i)if(owning(i))return false;
        for(auto index:scope.declared)if(owning(index))return false;
        return true;
    }
    // The host's view of the current scope for a host function's environment.
    std::shared_ptr<environment> host_scope(const FrameRecord& frame,const ExecutionOptions& options) {
        auto& record=objects(frame);
        return std::make_shared<environment>(record.scopes.back().ownership,host_of(options).owner()->baseEnv,
                                             record.frame,record.scopes.size()==1);
    }
    // Starts ending a scope as close_scope does. True when it has
    // destructors to run: the frame's pending driver then holds them.
    Cleanup& cleanup_of(ObjectFrame& record) {
        if(!record.cleanup) {
            if(free_cleanups.empty())record.cleanup=&cleanups.emplace_back();
            else {record.cleanup=free_cleanups.back();free_cleanups.pop_back();}
        }
        return *record.cleanup;
    }
    // Returns an idle cleanup (its driver reset, no unwind) to the pool.
    void release_cleanup(ObjectFrame& record) noexcept {
        auto* cleanup=record.cleanup;
        if(!cleanup||cleanup->pending.active()||cleanup->pending.waiting||cleanup->unwind.mode!=Unwind::Mode::none)return;
        try{free_cleanups.push_back(cleanup);}catch(...){return;}
        record.cleanup=nullptr;
    }
    // A leaving frame returns whatever its cleanup still holds.
    void discard_cleanup(ObjectFrame& record) noexcept {
        auto* cleanup=record.cleanup;
        if(!cleanup)return;
        reset_pending(cleanup->pending);cleanup->pending.waiting=false;cleanup->unwind=Unwind{};
        try{free_cleanups.push_back(cleanup);}catch(...){}
        record.cleanup=nullptr;
    }
    static void start_driver(ObjectFrame& record,Pending& pending,ObjectScope& scope,bool root,Tail tail) {
        auto& frame=*record.frame;
        pending.locals.emplace(frame,scope,root);
        pending.scope.emplace(scope.ownership.id(),root?frame.temporary_id():ownership::TempListId{},*pending.locals);
        pending.site=scope_site(record,scope);pending.tail=tail;
    }
    // Starts ending a scope that stays in place (the root) as
    // environment::cleanup does. True when it has destructors to run: the
    // frame's pending driver then holds them.
    bool begin_close(ObjectFrame& record,ObjectScope& scope,bool root,Tail tail) {
        auto& own=scope.ownership.record();
        if(own.closing)return false;
        auto& frame=*record.frame;
        if(idle(frame,scope,root)){own.closing=true;ObjectLocals(frame,scope,root).undeclare();return false;}
        start_driver(record,cleanup_of(record).pending,scope,root,tail);return true;
    }
    // Ends the innermost scope and pops it: at once when nothing can run,
    // otherwise through the pending driver, which keeps the scope until done.
    bool close_innermost(ObjectFrame& record,Tail tail) {
        auto& scope=record.scopes.back();
        auto& own=scope.ownership.record();auto& frame=*record.frame;
        if(own.closing||idle(frame,scope,false)) {
            if(!own.closing){own.closing=true;ObjectLocals(frame,scope,false).undeclare();}
            record.scopes.pop_back();return false;
        }
        auto& pending=cleanup_of(record).pending;
        pending.closing.emplace(std::move(scope));record.scopes.pop_back();
        start_driver(record,pending,*pending.closing,false,tail);return true;
    }
    static void reset_pending(Pending& pending) noexcept {
        pending.scope.reset();pending.temporaries.reset();pending.release.reset();pending.heap.reset();
        pending.block.reset();pending.rollback.clear();if(pending.failure)pending.failure=pending_error{};
        pending.locals.reset();pending.closing.reset();pending.storage.reset();
        pending.site={};pending.tail=Tail::none;
    }
    // The next call a pending driver needs, or null when it is done. A block
    // traversal's own failure ends it natively, as finalize_block does.
    static const lifecycle::DestructorCall* next_call(Pending& pending) {
        if(!pending.block)return pending.step([](auto& driver){return driver.advance();});
        try {
            if(const auto* block=pending.block->advance()){pending.call=lifecycle::destructor_call(*block);return &pending.call;}
        } catch(...) {pending.failure.capture_current();pending.block->abandon();}
        return nullptr;
    }
    static void start_partial(Pending& pending) {
        auto& [block,context]=pending.rollback[pending.next];
        pending.block.emplace(block,true,pending.failure);pending.site=context;
    }
    // Inside a failed copy's catch: when it left partial copies, the frame's
    // driver ends them (their destructors as continuations) and then raises
    // the copy's error at this instruction. False when there are none.
    bool begin_rollback(const FrameRecord& frame,blocks::copy_rollback& rollback) {
        if(rollback.partial.empty())return false;
        auto& pending=cleanup_of(objects(frame)).pending;
        pending.rollback=std::move(rollback.partial);rollback.partial.clear();pending.next=0;
        pending.failure=pending_error{};pending.failure.capture_current();
        start_partial(pending);pending.tail=Tail::rollback;return true;
    }
    static void call_returned(Pending& pending) {
        if(pending.block)pending.block->call_returned();else pending.step([](auto& driver){driver.call_returned();});
    }
    static void call_failed(Pending& pending) {
        if(pending.block)pending.block->call_failed();else pending.step([](auto& driver){driver.call_failed();});
    }
    enum class Entry {native,pushed,failed};
    // A destructor of this script runs above the waiting frame through the
    // path a native dispatch takes (invoke_bound_function, then
    // ofunction::invoke_bound and its entry into this executor) with the same
    // entries, checks, admission and trace frames, except the native stack
    // check. Its failure is delivered to the waiting driver.
    Entry enter_continuation(FrameId below,const lifecycle::DestructorCall& call,ExecutionPosition& position,const ExecutionOptions& options) {
        const auto owner=host_of(options).owner();
        if(call.owner->lock()!=owner)return Entry::native;
        const auto target=script_functions.find(call.function_id);
        if(target==script_functions.end())return Entry::native;
        auto& pending=objects(frames.resolve(below)).cleanup->pending;
        const bool reported=options.callbacks&&options.callbacks->error_reported();
        auto dispatch=trace::open_entry::begin();
        try {
            auto fn=std::dynamic_pointer_cast<ofunction>(owner->getFunction(call.function_id));
            if(!fn)throw std::runtime_error("Object destructor must be a script function void(address)");
            auto entry=trace::open_entry::begin();
            try {
                bool entered=false;
                try {
                    auto bound=fn->script_owner.lock();
                    if(!bound||!pending.site.record||pending.site.record->script.lock()!=bound)
                        throw std::runtime_error("Function belongs to another or unavailable script");
                    bound->require_execution();
                    if(!fn->linked)throw std::runtime_error("Function module has not been linked");
                    if(call.contexts->size()!=static_cast<std::size_t>(fn->hidden_count))
                        throw std::runtime_error("Generic context count mismatch");
                    validate_type_contexts(*call.contexts,bound);
                    auto admitted=options.calls?options.calls->enter():admission::accounting::ticket{};
                    admission::check_arguments("Script function",fn->function_id,fn->param_types,1,
                        [](std::size_t){return std::optional<int>(ADDRESS_VALUE);},true);
                    const Value argument(call.object);const auto region_mark=regions.size();
                    entered=true;
                    ContinuationEntry continuation{std::move(admitted),pending.site,call.contexts};
                    auto read=[&](std::size_t)->const Value&{return argument;};
                    enter_frame<true>(target->second,1,read,below,no_index,position,options,false,&continuation);
                    pending.region_mark=region_mark;
                } catch(...) {
                    // Before its entry into the executor the function notes its header.
                    if(!entered)trace::note_script_frame(fn->function_id,fn->debug.get(),0);
                    throw;
                }
            } catch(...) {entry.fail();throw;}
            pending.waiting=true;pending.reported=reported;pending.callee=frames.top_id();
            pending.dispatch=dispatch;pending.function=entry;trace_touched=true;
            return Entry::pushed;
        } catch(...) {
            dispatch.fail();
            if(options.callbacks)options.callbacks->restore_error_reported(reported);
            trace_touched=true;call_failed(pending);return Entry::failed;
        }
    }
    // Advances a frame's pending cleanup until it yields a continuation frame
    // (true) or completes (false); its first error then leaves at the waiting
    // instruction, as the native cleanup's did.
    bool drive(FrameId id,ExecutionPosition& position,const ExecutionOptions& options) {
        // Frame records keep their address while continuations push others.
        auto& frame=frames.resolve(id);auto& record=objects(frame);auto& pending=record.cleanup->pending;
        try {
            for(;;) {
                position=position_of(frame);
                const auto* call=next_call(pending);
                if(!call&&pending.tail==Tail::rollback) {
                    // Each partial copy ended in its own catch, which raised
                    // the error again before the next one.
                    pending.block.reset();
                    if(++pending.next<pending.rollback.size()) {
                        try{pending.failure.rethrow();}catch(...){pending.failure=pending_error{};pending.failure.capture_current();}
                        start_partial(pending);continue;
                    }
                    auto failure=std::move(pending.failure);position=position_of(frame);failure.rethrow();
                }
                if(!call) {
                    if(pending.tail==Tail::unwinding) {
                        try {pending.step([](auto& driver){driver.finish();});}
                        catch(...) {record.cleanup->unwind.failure.capture_current();}
                    } else {
                        // A drop clears its storage before raising the first error.
                        if(pending.tail==Tail::dropped)pending.storage->setValue(nullptr);
                        pending.step([](auto& driver){driver.finish();});
                    }
                    reset_pending(pending);release_cleanup(record);return false;
                }
                const auto entry=enter_continuation(id,*call,position,options);
                if(entry==Entry::pushed)return true;
                if(entry==Entry::failed)continue;
                try {runtime(options,[&]{lifecycle::dispatch(*call,pending.site);});call_returned(pending);}
                catch(...) {call_failed(pending);}
            }
        } catch(...) {
            const bool unwinding=pending.tail==Tail::unwinding;reset_pending(pending);
            if(!unwinding){position=position_of(frame);release_cleanup(record);throw;}
            record.cleanup->unwind.failure.capture_current();return false;
        }
    }
    // The continuation of a waiting frame returned: end its entries as their
    // scopes would, then resume the driver.
    void continuation_returned(FrameId id,ExecutionPosition& position,const ExecutionOptions& options) {
        auto& pending=objects(frames.resolve(id)).cleanup->pending;
        pending.waiting=false;pending.function.succeed();pending.dispatch.succeed();
        if(options.callbacks)options.callbacks->restore_error_reported(pending.reported);
        call_returned(pending);
        resume(id,position,options);
    }
    // True when id runs a waiting frame's destructor.
    bool continuation(FrameId id) {
        const auto& frame=frames.resolve(id);
        if(!frame.caller)return false;
        const auto& below=frames.resolve(frame.caller);
        if(below.object==no_index)return false;
        const auto* cleanup=objects(below).cleanup;
        return cleanup&&cleanup->pending.waiting&&cleanup->pending.callee==id;
    }
    static void level_ended(Unwind& unwind) noexcept {
        try{unwind.failure.rethrow();}catch(...){unwind.failure=pending_error{};unwind.failure.capture_current();}
    }
    // Ends the next levels of an unwinding frame. True when a level's
    // destructor runs as a continuation first; false when all are done.
    bool advance_unwind(FrameId id,ExecutionPosition& position,const ExecutionOptions& options) {
        for(;;) {
            auto& record=objects(frames.resolve(id));auto& unwind=record.cleanup->unwind;auto& pending=record.cleanup->pending;
            if(pending.active()) {
                const bool root=unwind.mode==Unwind::Mode::root;
                if(drive(id,position,options))return true;
                if(root)unwind.mode=Unwind::Mode::finished;else level_ended(unwind);
                continue;
            }
            if(unwind.mode==Unwind::Mode::levels&&record.controls.size()>unwind.target) {
                const auto control=record.controls.back();record.controls.pop_back();
                try {
                    if(control.scope) {
                        if(close_innermost(record,Tail::unwinding))continue;
                    } else if(record.frame->temporaries.size()>control.temporaries.count) {
                        pending.temporaries.emplace(control.temporaries.list,control.temporaries.count);
                        pending.site=scope_site(record,record.scopes.back());pending.tail=Tail::unwinding;continue;
                    }
                } catch(...) {reset_pending(pending);unwind.failure.capture_current();}
                level_ended(unwind);continue;
            }
            if(unwind.mode==Unwind::Mode::levels) {
                if(unwind.region){unwind.mode=Unwind::Mode::finished;continue;}
                unwind.mode=Unwind::Mode::root;
                try {if(begin_close(record,record.scopes.front(),true,Tail::unwinding))continue;}
                catch(...) {reset_pending(pending);unwind.failure.capture_current();}
                unwind.mode=Unwind::Mode::finished;continue;
            }
            return false;
        }
    }
    // A region of the unwinding frame recovers from its first error.
    void recover_region(FrameRecord& frame,CleanupTransition failure,ExecutionPosition& position,pending_error& resumed) {
        while(frame.scope_count>failure.watermark.scopes)leave_scope(frame);
        frame.statement_depth=failure.watermark.statements;frame.loop_depth=failure.watermark.loops;
        auto next=regions.unwound(frames.top_id(),frame);frame.pc=next.pc;
        // A failing finalizer resumes the body's first failure, if any, else
        // its own; either way at the original innermost position.
        if(next.kind==CleanupTransition::Kind::resume){position=next.position;resumed=std::move(next.failure);}
    }
    // An unwinding frame's levels ended after continuations: the error leaves
    // again (re-entering the failure walk) or its region recovers.
    void finish_unwind(FrameId id,ExecutionPosition& position,pending_error& resumed) {
        auto& frame=frames.resolve(id);auto& record=objects(frame);auto& unwind=record.cleanup->unwind;
        position=unwind.position;
        if(unwind.region) {
            try{unwind.failure.rethrow();}catch(...){}
            auto transition=std::move(unwind.transition);unwind=Unwind{};release_cleanup(record);
            recover_region(frame,std::move(transition),position,resumed);return;
        }
        unwind.republished=true;unwind.failure.rethrow();
    }
    // Resumes a frame whose continuation ended. A region's finalizer that
    // resumes a failure raises it here, as the loop start does after a
    // recovery inside the dispatch catch.
    void resume(FrameId id,ExecutionPosition& position,const ExecutionOptions& options) {
        auto& record=objects(frames.resolve(id));
        if(record.cleanup->unwind.mode==Unwind::Mode::none){drive(id,position,options);return;}
        if(advance_unwind(id,position,options))return;
        pending_error again;finish_unwind(id,position,again);
        if(again)again.rethrow();
    }
    // Inside the active catch block. Frames leave top first, each ending its
    // levels (destructors of this script run as continuations, after which
    // the error re-enters here) until a region recovers (true), a
    // continuation's failure reaches its waiting frame (true), or the error
    // leaves the invocation (false).
    bool fail(std::size_t mark,std::size_t region_mark,ExecutionPosition& position,
            const ExecutionOptions& options,pending_error& resumed) {
        for(;;) {
            if(frames.size()==mark)return false;
            const auto id=frames.top_id();auto& frame=frames.top();
            const bool region=regions.size()>region_mark&&regions.owner()==id;
            if(frame.object!=no_index) {
                auto& record=objects(frame);auto& unwind=cleanup_of(record).unwind;
                if(unwind.mode==Unwind::Mode::none) {
                    unwind.region=region;
                    if(region){unwind.transition=regions.failed(id,frame,&position);unwind.target=unwind.transition.watermark.controls;}
                    else unwind.target=0;
                    unwind.failure.capture_current();unwind.position=position;unwind.mode=Unwind::Mode::levels;
                }
                if(unwind.mode!=Unwind::Mode::finished&&advance_unwind(id,position,options))return true;
                if(!unwind.republished){try{unwind.failure.rethrow();}catch(...){}}
                position=unwind.position;
                if(unwind.region) {
                    auto transition=std::move(unwind.transition);unwind=Unwind{};release_cleanup(record);
                    recover_region(frame,std::move(transition),position,resumed);return true;
                }
                unwind=Unwind{};release_cleanup(record);
            } else if(region) {
                recover_region(frame,regions.failed(id,frame,&position),position,resumed);return true;
            }
            if(options.callbacks)options.callbacks->note_script_error(position_of(frame));
            if(continuation(id)) {
                // Its native invocation root ends here: no nested-frame
                // publication, then both entries publish and the waiting
                // driver captures the error.
                const auto below=frame.caller;
                cleanup(frame);frames.pop();
                auto& pending=objects(frames.resolve(below)).cleanup->pending;
                regions.discard(pending.region_mark);
                pending.waiting=false;pending.function.fail();pending.dispatch.fail();
                if(options.callbacks)options.callbacks->restore_error_reported(pending.reported);
                call_failed(pending);
                drive_pending=below;return true;
            }
            if(frame.caller)trace::publish_current();
            cleanup(frame);frames.pop();
        }
    }
    static ObjectHost& host_of(const ExecutionOptions& options) {
        auto* host=options.callbacks?options.callbacks->objects():nullptr;
        if(!host)throw std::invalid_argument("Scalar VM object frames require a script object host");
        return *host;
    }
    // Brackets object-model helpers that may run script code natively.
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
    // Runs an object operation whose failed copy may leave partial copies:
    // their destructors run in the loop before its error is raised. True when
    // the frame now waits for them.
    template<class Operation> bool with_rollback(const FrameRecord& frame,ExecutionPosition& position,
            const ExecutionOptions& options,Operation operation) {
        // Empty between uses: a nested root can only start while it is.
        auto& rollback=partial_copies;
        try {runtime(options,[&]{operation(&rollback);});}
        catch(...) {
            bool waits;
            try{waits=begin_rollback(frame,rollback);}catch(...){rollback.partial.clear();throw;}
            if(!waits)throw;
            drive(frames.top_id(),position,options);return true;
        }
        return false;
    }
    void store(const FrameRecord& frame,Register destination,Register target,Register source,
               ExecutionPosition& position,const ExecutionOptions& options) {
        auto& reg=registers[frame.registers.begin+target];
        if(!reg.reference)throw std::logic_error("Scalar VM store target is not a slot reference");
        if(reg.host) {
            // Assignment can copy, replace or finalize objects (destructors).
            auto storage=reg.host;auto value=variable_of(frame,source);
            if(value->type_tag()==OBJECT_VALUE||storage->type_tag()==OBJECT_VALUE) {
                if(with_rollback(frame,position,options,[&](blocks::copy_rollback* deferred){blocks::store(storage,value,site(frame),deferred);}))
                    return;
            } else blocks::store(storage,value,site(frame));
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
    void cleanup(FrameRecord& frame) noexcept {
        // clear's body, kept inline here whatever the inliner does with clear.
        for(std::size_t i=0;i<frame.registers.count;++i) {
            auto& reg=registers[frame.registers.begin+i];
            if(reg.reference)drop_reference(reg);
            reg.value=Value{};
        }
        for(std::size_t i=0;i<frame.locals.count;++i)if(local(frame,i)!=no_index)release(local(frame,i));
        registers.truncate(frame.registers.begin);locals.truncate(frame.locals.begin);
        declared.truncate(frame.declared.begin);scopes.truncate(frame.scope_marks.begin);
        if(frame.object!=no_index) {
            // Normal and error exits have already run every scope cleanup.
            if(frame.object+1!=object_frames.size())std::terminate();
            discard_cleanup(object_frames.back());
            object_frames.pop_back();frame.object=no_index;
        }
    }
    ownership::Site calling_scope(FrameId caller,const ExecutionOptions& options) {
        if(!caller)return options.caller;
        auto& record=frames.resolve(caller);
        return record.object==no_index?ownership::Site{}:site(record);
    }
    // Object frames are entered out of line, which keeps the scalar
    // dispatch loop's inlining independent of the object model.
    template<class Argument> [[gnu::noinline]] void enter_object_frame(std::uint32_t index,std::size_t count,Argument& argument,
            FrameRecord& frame,const FrameLayout& layout,const ExecutionOptions& options,ownership::Site caller_site,
            const type_contexts* contexts) {
        const auto& function=program.functions[index];
        // Same order as the tree executor: parameters are stored from
        // the calling scope, then bound to the new function root.
        auto& host=host_of(options);
        const auto calling=caller_site.record?caller_site:calling_scope(frame.caller,options);
        if(!calling.record)throw std::logic_error("Scalar VM object frame has no calling scope");
        const auto owner=host.owner();
        // Built in place: a pending cleanup's records do not move.
        const auto object=static_cast<std::uint32_t>(object_frames.size());
        auto& record=object_frames.emplace_back();
        try {
        record.frame=std::make_shared<function_frame>(host.ownership_arena());
        auto& tree=*record.frame;
        tree.global_offset=function.global_offset;tree.global_count=function.global_count;tree.param_count=count;
        tree.slots.resize(function.param_types.size()+static_cast<std::size_t>(function.local_count));
        if(contexts&&!contexts->empty())tree.hidden_contexts=*contexts;
        if(function.bound_context_count)record.bound.resize(function.bound_context_count);
        record.scopes.emplace_back(host.ownership_arena(),
                                   ownership::Scope::Links{owner->baseEnv->ownership_id(),calling.scope(),tree.temporary_id()});
        record.scopes.back().ownership.record().script=owner;
        const auto root=scope_site(record,record.scopes.back());
        for(std::size_t i=0;i<count;++i) {
            auto storage=std::make_shared<variable>();auto value=wrap(argument(i));
            if(value->type_tag()==OBJECT_VALUE)runtime(options,[&]{blocks::store(storage,value,calling);});
            else blocks::store(storage,value,calling);
            tree.slots[i]=storage;blocks::bind_local(storage,root);
        }
        } catch(...) {object_frames.pop_back();throw;}
        registers.append_default(layout.registers);scopes.append_default(layout.scope_marks);
        frame.object=object;
        try{frames.push(std::move(frame));}catch(...){cleanup(frame);throw;}
    }
    // A continuation frame arrives with its admission and the calling site
    // of its native dispatch; its entries publish its failures.
    struct ContinuationEntry {admission::accounting::ticket admitted;ownership::Site caller;const type_contexts* contexts;};
    // contexts are the callee's hidden contexts. A script call checks them
    // before its admission, as ofunction::invoke_bound does; a public entry
    // and a continuation have checked them already.
    template<class Argument> void enter(std::uint32_t index,std::size_t count,Argument argument,
            FrameId caller,Register destination,ExecutionPosition& position,const ExecutionOptions& options,bool admit,
            const type_contexts* contexts=nullptr) {
        enter_frame<false>(index,count,argument,caller,destination,position,options,admit,nullptr,contexts);
    }
    template<bool Continuation,class Argument> void enter_frame(std::uint32_t index,std::size_t count,Argument& argument,
            FrameId caller,Register destination,ExecutionPosition& position,const ExecutionOptions& options,bool admit,
            ContinuationEntry* continuation,const type_contexts* contexts=nullptr) {
        const ExecutionPosition entry{index,0,0,0,no_index};
        try {
            if(caller&&trace_touched)trace::discard_active();
            FrameRecord frame;frame.function=index;frame.caller=caller;frame.return_register=destination;
            const auto& function=program.functions[index];
            if constexpr(Continuation) {
                frame.admission=std::move(continuation->admitted);contexts=continuation->contexts;
            } else {
                if(caller) {
                    if((contexts?contexts->size():0)!=function.hidden_count)throw std::runtime_error("Generic context count mismatch");
                    if(contexts&&!contexts->empty())validate_type_contexts(*contexts,host_of(options).owner());
                }
                if(admit&&options.calls)frame.admission=options.calls->enter();
            }
            const bool objects=object_function[index];
            validate_arguments(function,count,argument,objects);
            const auto layout=layouts[index];
            frame.pc=function.begin;frame.end=tier.entry_bound(index);
            frame.registers={registers.size(),layout.registers};frame.locals={locals.size(),layout.locals};
            frame.declared={declared.size(),layout.locals};frame.scope_marks={scopes.size(),layout.scope_marks};
            // Preflight every backing array before publishing any live range.
            // Argument readers use offsets, so relocating caller registers is safe.
            registers.prepare_append(layout.registers);locals.prepare_append(layout.locals);
            declared.prepare_append(layout.locals);scopes.prepare_append(layout.scope_marks);
            regions.prepare(layout.cleanups);
            if(objects) {
                if constexpr(Continuation)enter_object_frame(index,count,argument,frame,layout,options,continuation->caller,contexts);
                else enter_object_frame(index,count,argument,frame,layout,options,ownership::Site{},contexts);
                return;
            }
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
            if(!Continuation&&caller){trace_touched=true;trace::publish_current();}
            throw;
        }
    }
    // A context as the tree's bind_context builds it: a reference names one of
    // the frame's hidden contexts, any other context is bound anew.
    std::shared_ptr<const type_context> bind(const FrameRecord& frame,std::uint32_t index,std::size_t depth=0) {
        if(depth>=128)throw std::runtime_error("Generic context nesting exceeds 128 levels");
        const auto& spec=function_of(frame).contexts[index];
        if(spec.reference) {
            if(frame.object==no_index||*spec.reference>=objects(frame).frame->hidden_contexts.size())
                throw std::runtime_error("Generic context reference is unavailable");
            return objects(frame).frame->hidden_contexts[*spec.reference];
        }
        auto result=std::make_shared<type_context>();
        result->abi=spec.abi;result->kind=spec.kind;result->width=static_cast<int>(spec.width);
        if(spec.factory)result->factory_id=program.targets[*spec.factory].function_id;
        if(spec.placement)result->placement_id=program.targets[*spec.placement].function_id;
        for(const auto child:spec.contexts)result->contexts.push_back(bind(frame,child,depth+1));
        for(const auto child:spec.placement_contexts)result->placement_contexts.push_back(bind(frame,child,depth+1));
        if(spec.element)result->element=bind(frame,*spec.element,depth+1);
        return result;
    }
    type_contexts bind_all(const FrameRecord& frame,const std::vector<std::uint32_t>& indices) {
        type_contexts result;result.reserve(indices.size());
        for(const auto index:indices)result.push_back(bind(frame,index));
        return result;
    }
    // A call's contexts, bound into a reused list; the callee's frame copies
    // them before anything can bind again.
    [[gnu::noinline]] const type_contexts& call_contexts(const FrameRecord& frame,const std::vector<std::uint32_t>& indices) {
        bound_call.clear();bound_call.reserve(indices.size());
        for(const auto index:indices)bound_call.push_back(bind(frame,index));
        return bound_call;
    }
    bool constant_context(const FunctionRecord& function,std::uint32_t index) const {
        const auto& spec=function.contexts[index];
        if(spec.reference)return false;
        for(const auto child:spec.contexts)if(!constant_context(function,child))return false;
        for(const auto child:spec.placement_contexts)if(!constant_context(function,child))return false;
        return !spec.element||constant_context(function,*spec.element);
    }
    const std::shared_ptr<const type_context>& bound(const FrameRecord& frame,Register index) {
        const auto& result=objects(frame).bound[index];
        if(!result)throw std::logic_error("Scalar VM generic context was not bound");
        return result;
    }
    // A generic default as the tree's context node makes it. A class default
    // runs its factory as a script call from this site; its result joins the
    // frame's temporaries before the factory's result is checked.
    void context_default(const FrameRecord& frame,Register destination,const std::shared_ptr<const type_context>& context,
            const ExecutionOptions& options) {
        if(context->kind==4) {
            auto result=std::make_shared<variable>(blocks::create_buffer(context->element,host_of(options).owner()));
            blocks::adopt_result(result,site(frame));reference(frame,destination,std::move(result));return;
        }
        if(context->kind==3) {
            if(!context->factory_id)throw std::runtime_error("Generic type has no default constructor");
            std::shared_ptr<variable> result;
            runtime(options,[&]{result=invoke_bound_function(host_of(options).owner(),*context->factory_id,site(frame),{},context->contexts);});
            blocks::adopt_result(result,site(frame));
            auto block=blocks::of(result);
            if(!block||block->buffer||block->slots.size()!=static_cast<std::size_t>(context->width))
                throw std::runtime_error("Generic default factory must return a class with the declared slot width");
            reference(frame,destination,std::move(result));return;
        }
        switch(context->abi) {
        case INT_VALUE:value(frame,destination,Value(0));return;
        case STRING_VALUE:value(frame,destination,Value(std::string()));return;
        case FLOAT_VALUE:value(frame,destination,Value(0.0f));return;
        case DOUBLE_VALUE:value(frame,destination,Value(0.0));return;
        case BOOLEAN_VALUE:value(frame,destination,Value(false));return;
        case ADDRESS_VALUE:value(frame,destination,Value(address{}));return;
        default:throw std::runtime_error("Generic type cannot be default initialized");
        }
    }
    static bool same_context_layout(const std::shared_ptr<const type_context>& a,const std::shared_ptr<const type_context>& b,std::size_t depth=0) {
        if(!a||!b||depth>=128)return false;
        return a->abi==b->abi&&a->kind==b->kind&&a->width==b->width&&
            (a->kind!=4||same_context_layout(a->element,b->element,depth+1));
    }
    // The tree's check_object node, after its value and context.
    void check_object(const FrameRecord& frame,Register source,const std::shared_ptr<const type_context>& context,
            const std::vector<int>& slot_types) {
        const auto& checked=read(frame,source);
        if(checked.type_tag()!=context->abi)throw std::runtime_error("Object check failed: value has an incompatible type");
        if(context->kind==2){heap::check_object(checked.get<address>(),slot_types);return;}
        if(context->kind!=3&&context->kind!=4)return;
        auto block=blocks::of(variable_of(frame,source));
        if(!block||block->state!=slot_block::status::live)throw std::runtime_error("Object check failed: value object has expired");
        if(context->kind==4) {
            if(!block->buffer||!same_context_layout(context->element,block->buffer->element))
                throw std::runtime_error("Object check failed: buffer element context mismatch");
            return;
        }
        if(block->buffer||block->slots.size()!=static_cast<std::size_t>(context->width)||
           (!slot_types.empty()&&block->slots.size()!=slot_types.size()))
            throw std::runtime_error("Object check failed: class value width mismatch");
        for(std::size_t i=0;i<slot_types.size();++i)
            if(slot_types[i]!=ANY_VALUE&&(!block->slots[i]||block->slots[i]->type_tag()!=slot_types[i]))
                throw std::runtime_error("Object check failed: slot "+std::to_string(i)+" has an incompatible type");
    }
    // A table whose contexts are all constant is bound once, as the tree's
    // set_methods node caches it.
    std::shared_ptr<const method_table> methods(const FrameRecord& frame,std::uint32_t operand) {
        auto& cached=method_tables[frame.function][operand];
        if(cached)return cached;
        const auto& function=function_of(frame);const auto& operation=function.operations[operand];
        auto table=std::make_shared<method_table>();table->reserve(operation.methods.size());
        bool context_free=true;
        for(const auto& method:operation.methods) {
            for(const auto context:method.contexts)if(!constant_context(function,context))context_free=false;
            table->push_back({program.targets[method.target].function_id,bind_all(frame,method.contexts)});
        }
        if(context_free)cached=table;
        return table;
    }
    static address pointer(const Value& value,const char* message) {
        if(value.type_tag()!=ADDRESS_VALUE)throw std::runtime_error(message);
        return value.get<address>();
    }
    // A scalar frame as the JIT tier's native code sees it: each operation is
    // the corresponding instruction's own, with failure reported, not thrown.
    class TierFrame final:public jit::FrameAccess {
        State& vm;
        FrameRecord& frame;
        std::uint64_t& steps_;
        std::span<const SlotId> globals;
        RegisterValue& reg(std::uint32_t index) const noexcept{return vm.registers[frame.registers.begin+index];}
        std::uint32_t& local(std::uint32_t index) const noexcept{return vm.locals[frame.locals.begin+index];}
        Value& cell_value(std::uint32_t cell) const noexcept {
            auto* value=vm.pool.try_resolve(vm.cells[cell].slot);
            if(!value)std::terminate();
            return *value;
        }
    public:
        TierFrame(State& state,FrameRecord& record,std::uint64_t& steps,std::span<const SlotId> slots) noexcept
            :vm(state),frame(record),steps_(steps),globals(slots) {}
        bool register_shadow(std::uint32_t index,std::optional<jit::Shadow>& out) const noexcept override {
            const auto& value=reg(index);
            if(value.reference)return false;
            out=jit::shadow_of(value.value);return true;
        }
        void set_register_shadow(std::uint32_t index,const jit::Shadow& value) noexcept override {
            auto& target=reg(index);
            if(target.reference)std::terminate();
            target.value=jit::value_of(value);
        }
        bool local_shadow(std::uint32_t index,std::optional<jit::Shadow>& out) const noexcept override {
            const auto cell=local(index);
            if(cell==no_index)return false;
            out=jit::shadow_of(cell_value(cell));return true;
        }
        void set_local_shadow(std::uint32_t index,const jit::Shadow& value) noexcept override {
            const auto cell=local(index);
            if(cell==no_index)std::terminate();
            cell_value(cell)=jit::value_of(value);
        }
        std::uint8_t flags() const noexcept override {
            return static_cast<std::uint8_t>(frame.returned|frame.broken<<1|frame.continued<<2);
        }
        void set_flags(std::uint8_t flags) noexcept override {
            frame.returned=flags&1;frame.broken=flags&2;frame.continued=flags&4;
        }
        std::uint64_t steps() const noexcept override{return steps_;}
        void set_steps(std::uint64_t steps) noexcept override{steps_=steps;}
        void set_depths(std::uint32_t statement,std::uint32_t loop) noexcept override {
            frame.statement_depth=statement;frame.loop_depth=loop;
        }
        void set_pc(ProgramCounter pc) noexcept override{frame.pc=pc;}
        bool reference_local(std::uint32_t index,std::uint32_t slot) noexcept override {
            if(slot>=frame.locals.count||local(slot)==no_index)return false;
            vm.reference(frame,index,{},local(slot));return true;
        }
        bool reference_global(std::uint32_t index,std::uint32_t relative) noexcept override {
            const auto& function=vm.function_of(frame);
            if(relative>=function.global_count||function.global_offset>globals.size()||
               relative>=globals.size()-function.global_offset)return false;
            vm.reference(frame,index,globals[function.global_offset+relative],no_index);return true;
        }
        void copy_reference(std::uint32_t destination,std::uint32_t source) noexcept override {
            if(!reg(source).reference||reg(source).host)std::terminate();
            vm.copy(frame,destination,source);
        }
        void drop_reference(std::uint32_t index) noexcept override {
            if(auto& target=reg(index);target.reference)vm.drop_reference(target);
        }
        bool read_reference(std::uint32_t index,Value& out) const noexcept override {
            const auto& source=reg(index);
            if(!source.reference||source.host)return false;
            const auto* value=vm.pool.try_resolve(source.cell==no_index?source.global:vm.cells[source.cell].slot);
            if(!value)return false;
            out=*value;return true;
        }
        bool write_reference(std::uint32_t index,const Value& value) noexcept override {
            const auto& target=reg(index);
            if(!target.reference||target.host)return false;
            auto* storage=vm.pool.try_resolve(target.cell==no_index?target.global:vm.cells[target.cell].slot);
            if(!storage)return false;
            *storage=value;return true;
        }
        bool declare(std::uint32_t slot,const Value& initial) noexcept override {
            // Only without growth: an allocation that charges the workspace
            // (or fails) is the VM's to perform.
            if((vm.free_cell==no_index&&vm.cells.size()==vm.cells.capacity())||vm.pool.emplace_grows())return false;
            const auto cell=vm.allocate(initial);
            if(local(slot)!=no_index)vm.release(local(slot));
            else vm.declared[frame.declared.begin+frame.declared_count++]=slot;
            local(slot)=cell;return true;
        }
        void enter_scope() noexcept override {
            if(frame.scope_count==frame.scope_marks.count)std::terminate();
            vm.scopes[frame.scope_marks.begin+frame.scope_count++]=static_cast<std::uint32_t>(frame.declared_count);
        }
        void leave_scope() noexcept override{vm.leave_scope(frame);}
        bool undeclared(std::uint32_t slot) const noexcept override{return slot<frame.locals.count&&local(slot)==no_index;}
        void set_result(const Value& value,std::uint32_t location) noexcept override {
            frame.result=value;frame.return_location=location;
        }
    };
    // The frame reached its bound, FrameRecord::end. Only at the function's
    // real end is that the verified "every function ends" invariant failing.
    // True when native code ran: the frame's pc and bound then changed.
    [[gnu::noinline,gnu::cold]] bool reach_bound(FrameRecord& frame,std::uint64_t& steps,std::span<const SlotId> globals) {
        if(frame.pc>=program.functions[frame.function].end)throw std::logic_error("Scalar VM function did not terminate");
        TierFrame access(*this,frame,steps,globals);
        const auto reached=tier.reached(frame.function,frame.pc,access);
        frame.end=reached.bound;return reached.ran;
    }
    static bool batch_opcode(Opcode opcode) noexcept {
        switch(opcode) {
        case Opcode::tick:case Opcode::constant:case Opcode::load_slot:
        case Opcode::snapshot:case Opcode::copy_reference:case Opcode::truth:
        case Opcode::unary:case Opcode::binary:case Opcode::convert:return true;
        default:return false;
        }
    }
    // These operations cannot invoke script code, mutate slots or change the
    // active frame. Used by both the ordinary and batched dispatch paths.
    void scalar_instruction(FrameRecord& frame,const Instruction& instruction,std::span<const SlotId> globals) {
        switch(instruction.opcode) {
        case Opcode::constant:value(frame,instruction.result,program.constants[instruction.operand]);break;
        case Opcode::load_slot:load(frame,instruction.result,std::bit_cast<int>(instruction.operand),globals);break;
        case Opcode::snapshot:value(frame,instruction.result,read(frame,instruction.left));break;
        case Opcode::copy_reference:copy(frame,instruction.result,instruction.left);break;
        case Opcode::truth:value(frame,instruction.result,Value(value_ops::truth(read(frame,instruction.left))));break;
        case Opcode::unary:value(frame,instruction.result,value_ops::unary(static_cast<UnaryOperation>(instruction.operand),read(frame,instruction.left)));break;
        case Opcode::binary:value(frame,instruction.result,value_ops::binary(static_cast<BinaryOperation>(instruction.operand),read(frame,instruction.left),read(frame,instruction.right)));break;
        case Opcode::convert:value(frame,instruction.result,value_ops::convert(read(frame,instruction.left),static_cast<int>(instruction.operand)));break;
        default:throw std::logic_error("Invalid instruction in scalar budget batch");
        }
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
            {},no_index,position,options,options.admit_entry,options.contexts);
        // A failure resumed after its finalizer is raised again inside the
        // dispatch try, so outer regions handle it without native recursion.
        pending_error resumed;
        for(;;) try {
            if(resumed)std::exchange(resumed,pending_error{}).rethrow();
            if(drive_pending)resume(std::exchange(drive_pending,FrameId{}),position,options);
            while(frames.size()>mark) {
                auto& frame=frames.top();
                if(frame.pc>=frame.end)[[unlikely]]{if(reach_bound(frame,steps,globals))continue;}
                const auto& instruction=program.instructions[frame.pc];
                const auto& source=program.sources[frame.pc];
                frame.node=source.node;frame.location=source.location;++frame.pc;
                position=position_of(frame);
                switch(instruction.opcode) {
                case Opcode::tick: {
                    const auto batch=budget_batches[frame.pc-1];
                    if(options.batch_budget && frame.object==no_index && batch.ticks>1 && steps>=batch.ticks) {
                        --steps;
                        while(frame.pc<batch.end) {
                            const auto& next=program.instructions[frame.pc];
                            const auto& next_source=program.sources[frame.pc++];
                            frame.node=next_source.node;frame.location=next_source.location;
                            position=position_of(frame);
                            if(next.opcode==Opcode::tick)--steps;
                            else scalar_instruction(frame,next,globals);
                        }
                    } else {
                        if(!steps)throw std::runtime_error("Script execution step limit exceeded");
                        --steps;
                    }
                    break;
                }
                case Opcode::constant:case Opcode::load_slot:case Opcode::snapshot:
                case Opcode::copy_reference:case Opcode::truth:case Opcode::unary:
                case Opcode::binary:case Opcode::convert:scalar_instruction(frame,instruction,globals);break;
                case Opcode::check_declaration:check_declaration(frame,std::bit_cast<int>(instruction.operand),function_of(frame));break;
                case Opcode::declare_slot: {
                    const auto slot=instruction.operand;
                    if(frame.object!=no_index) {
                        auto storage=std::make_shared<variable>();
                        const auto here=site(frame);
                        if(instruction.left!=no_index) {
                            auto initial=variable_of(frame,instruction.left);
                            if(initial->type_tag()==OBJECT_VALUE) {
                                if(with_rollback(frame,position,options,[&](blocks::copy_rollback* deferred){blocks::store(storage,initial,here,deferred);}))
                                    break;
                            } else blocks::store(storage,initial,here);
                        }
                        // A hand-written initializer may have declared this
                        // slot meanwhile; the tree executor replaces it too.
                        auto& record=objects(frame);
                        record.scopes.back().declared.push_back(slot);record.frame->slots[slot]=storage;
                        blocks::bind_local(storage,here);
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
                case Opcode::store:store(frame,instruction.result,instruction.left,instruction.right,position,options);break;
                case Opcode::materialize: {
                    // Arguments snapshot an owned object before the next one
                    // can change it; scalars are plain value copies.
                    const auto& current=read(frame,instruction.left);
                    if(frame.object==no_index||current.type_tag()!=OBJECT_VALUE){value(frame,instruction.result,current);break;}
                    auto source=variable_of(frame,instruction.left);std::shared_ptr<variable> result;
                    if(with_rollback(frame,position,options,[&](blocks::copy_rollback* deferred){result=blocks::materialize(source,site(frame),deferred);}))
                        break;
                    value(frame,instruction.result,value_access::read(*result));break;
                }
                case Opcode::jump:frame.pc=instruction.target;break;
                case Opcode::branch_false:if(!value_ops::truth(read(frame,instruction.left)))frame.pc=instruction.target;break;
                case Opcode::branch_flow:if(frame.returned||frame.broken||frame.continued)frame.pc=instruction.target;break;
                case Opcode::branch_returned:if(frame.returned)frame.pc=instruction.target;break;
                case Opcode::scope_enter:
                    if(frame.scope_count==frame.scope_marks.count)throw std::logic_error("Scalar VM scope capacity exceeded");
                    if(frame.object!=no_index) {
                        auto& record=objects(frame);
                        record.controls.reserve(record.controls.size()+1);
                        const auto parent=record.scopes.back().ownership.id();
                        auto script=record.scopes.back().ownership.record().script;
                        record.scopes.emplace_back(host_of(options).ownership_arena(),
                                                   ownership::Scope::Links{parent,{},record.frame->temporary_id()});
                        record.scopes.back().ownership.record().script=std::move(script);
                        record.controls.push_back({true,{}});
                    }
                    scopes[frame.scope_marks.begin+frame.scope_count++]=static_cast<std::uint32_t>(frame.declared_count);break;
                case Opcode::scope_leave:
                    leave_scope(frame);
                    if(frame.object!=no_index) {
                        auto& record=objects(frame);
                        if(record.controls.empty()||!record.controls.back().scope)throw std::logic_error("Scalar VM object scope records are unbalanced");
                        record.controls.pop_back();
                        if(close_innermost(record,Tail::scope_closed))drive(frames.top_id(),position,options);
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
                        if(record.frame->temporaries.size()>mark.count) {
                            auto& pending=cleanup_of(record).pending;
                            pending.temporaries.emplace(mark.list,mark.count);
                            pending.site=scope_site(record,record.scopes.back());pending.tail=Tail::statement_done;
                            drive(frames.top_id(),position,options);
                        }
                    }
                    break;
                case Opcode::loop_enter:++frame.loop_depth;break;
                case Opcode::loop_leave:--frame.loop_depth;break;
                case Opcode::loop_next:
                    if(frame.broken){frame.broken=false;frame.pc=instruction.operand;}
                    else {frame.continued=false;frame.pc=instruction.target;}
                    if(tier.reports_backedges())[[unlikely]]frame.end=tier.backedge(frame.function,frame.pc,frame.end);
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
                        // Bound after the arguments, as the tree's call node binds them.
                        const type_contexts* contexts=nullptr;
                        if(!operation.contexts.empty()||target.hidden_count)[[unlikely]]contexts=&call_contexts(frame,operation.contexts);
                        enter(target.function,operation.arguments.size(),
                            [&](std::size_t i)->const Value&{return read(frame,operation.arguments[i]);},
                            frames.top_id(),instruction.result,position,options,true,contexts);
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
                            const auto scope=host_scope(frame,options);
                            trace_touched=true;
                            auto output=options.callbacks->invoke_external_in(target,snapshots.span(),position,scope);
                            blocks::adopt_result(output,site(frame));
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
                    const auto scope=site(frame);
                    auto source=variable_of(frame,instruction.left);Value result;
                    if(source->type_tag()==OBJECT_VALUE) {
                        std::shared_ptr<variable> exported;
                        if(with_rollback(frame,position,options,[&](blocks::copy_rollback* deferred){exported=blocks::export_result(source,scope,deferred);}))
                            break;
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
                        CleanupWatermark{{},frame.scope_count,0,frame.statement_depth,frame.loop_depth,
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
                    heap::register_object(object,destructor,operation.manual,site(frame),bind_all(frame,operation.contexts));
                    value(frame,instruction.result,Value{});break;
                }
                case Opcode::object_delete: {
                    const auto object=pointer(read(frame,instruction.left),"Object pointer requires address type");
                    value(frame,instruction.result,Value{});
                    if(heap::begin_delete(object)) {
                        auto& pending=cleanup_of(objects(frame)).pending;
                        pending.heap.emplace(object);pending.site=site(frame);pending.tail=Tail::none;
                        drive(frames.top_id(),position,options);
                    }
                    break;
                }
                case Opcode::move_out: {
                    const auto slot=std::bit_cast<int>(instruction.operand);
                    if(slot<0){value(frame,instruction.result,pool.resolve(global_slot(slot,frame,globals)));break;}
                    auto moved=blocks::move_out(object_local(frame,slot),site(frame));
                    value(frame,instruction.result,value_access::read(*moved));break;
                }
                case Opcode::drop: {
                    // Only object storage can own a value; globals never do.
                    auto& target=registers[frame.registers.begin+instruction.left];
                    auto storage=target.reference?target.host:nullptr;
                    value(frame,instruction.result,Value{});
                    if(storage) {
                        if(auto block=blocks::begin_drop(storage)) {
                            auto& pending=cleanup_of(objects(frame)).pending;
                            pending.release.emplace(std::move(block),true);pending.storage=std::move(storage);
                            pending.site=site(frame);pending.tail=Tail::dropped;
                            drive(frames.top_id(),position,options);
                        }
                    }
                    break;
                }
                case Opcode::check_method_receiver:
                    pointer(read(frame,instruction.left),"Virtual method receiver must be address");break;
                case Opcode::set_methods:
                    heap::set_methods(pointer(read(frame,instruction.left),"Virtual method receiver must be address"),
                        methods(frame,instruction.operand),host_of(options).owner());
                    value(frame,instruction.result,Value{});break;
                case Opcode::call_virtual: {
                    const auto& operation=function_of(frame).operations[instruction.operand];
                    if(operation.arguments.empty())throw std::runtime_error("Virtual method receiver must be address");
                    const auto receiver=pointer(read(frame,operation.arguments[0]),"Virtual method receiver must be address");
                    const auto table=heap::methods_at(receiver,host_of(options).owner());
                    const auto slot=static_cast<std::size_t>(static_cast<std::uint32_t>(operation.immediate));
                    if(slot>=table->size())throw std::runtime_error("Virtual method slot is out of range");
                    const auto& method=(*table)[slot];
                    const auto found=script_functions.find(method.function_id);
                    if(found==script_functions.end())throw std::runtime_error("Unknown function id: "+function_id_text(method.function_id));
                    enter(found->second,operation.arguments.size(),
                        [&](std::size_t i)->const Value&{return read(frame,operation.arguments[i]);},
                        frames.top_id(),instruction.result,position,options,true,&method.contexts);
                    auto& callee=frames.top();callee.check_return=true;callee.checked_return_type=operation.return_type;
                    break;
                }
                case Opcode::bind_context:
                    objects(frame).bound[instruction.result]=bind(frame,instruction.operand);break;
                case Opcode::context_abi:
                    value(frame,instruction.result,Value(bound(frame,instruction.left)->abi));break;
                case Opcode::context_default:
                    context_default(frame,instruction.result,bound(frame,instruction.left),options);break;
                case Opcode::check_type: {
                    // The checked value itself, as the tree's node returns it.
                    if(read(frame,instruction.left).type_tag()!=bound(frame,instruction.right)->abi)
                        throw std::runtime_error("Generic value type mismatch");
                    copy(frame,instruction.result,instruction.left);break;
                }
                case Opcode::return_typed: {
                    const auto context=bound(frame,instruction.right);
                    auto source=variable_of(frame,instruction.left);Value result;
                    if(source->type_tag()!=context->abi)throw std::runtime_error("Generic value type mismatch");
                    const auto scope=site(frame);
                    if(source->type_tag()==OBJECT_VALUE) {
                        std::shared_ptr<variable> exported;
                        if(with_rollback(frame,position,options,[&](blocks::copy_rollback* deferred){exported=blocks::export_result(source,scope,deferred);}))
                            break;
                        result=value_access::read(*exported);
                    } else {
                        result=value_access::read(*source);
                        if(context->kind==2)heap::return_object(result.get<address>(),scope);
                    }
                    frame.result=std::move(result);frame.returned=true;frame.return_location=frame.location;
                    value(frame,instruction.result,Value{});break;
                }
                case Opcode::buffer_new: {
                    auto result=std::make_shared<variable>(blocks::create_buffer(bound(frame,instruction.left),host_of(options).owner()));
                    blocks::adopt_result(result,site(frame));reference(frame,instruction.result,std::move(result));break;
                }
                case Opcode::buffer_operation: {
                    // Element constructors and destructors run natively from
                    // the host's view of this scope, as host functions do.
                    const auto& operation=function_of(frame).operations[instruction.operand];
                    auto buffer=blocks::of(variable_of(frame,instruction.left));
                    std::vector<std::shared_ptr<variable>> arguments;arguments.reserve(operation.arguments.size());
                    for(const auto argument:operation.arguments)arguments.push_back(variable_of(frame,argument));
                    std::shared_ptr<variable> result;
                    runtime(options,[&]{result=blocks::buffer_operation(static_cast<int>(operation.immediate),buffer,arguments,host_scope(frame,options));});
                    reference(frame,instruction.result,std::move(result));break;
                }
                case Opcode::value_compare:
                    value(frame,instruction.result,Value(blocks::compare_value(bound(frame,instruction.operand),
                        variable_of(frame,instruction.left),variable_of(frame,instruction.right),nullptr)));break;
                case Opcode::check_object:
                    check_object(frame,instruction.left,bound(frame,instruction.right),
                        function_of(frame).operations[instruction.operand].slot_types);
                    copy(frame,instruction.result,instruction.left);break;
                case Opcode::function_end: {
                    frame.location=frame.return_location;position=position_of(frame);
                    auto output=value_ops::convert_return(frame.result,function_of(frame).return_type);
                    if(frame.object!=no_index) {
                        // The root scope ends after return conversion, as the
                        // tree executor's with_cleanup does.
                        // Its destructors run above this frame; the instruction
                        // then runs again with the root already closed.
                        auto& record=objects(frame);
                        if(begin_close(record,record.scopes.front(),true,Tail::none)&&drive(frames.top_id(),position,options)){--frame.pc;break;}
                    }
                    const auto caller=frame.caller;const auto destination=frame.return_register;
                    const bool check=frame.check_return;const int expected=frame.checked_return_type;
                    const auto self=frames.top_id();
                    cleanup(frame);frames.pop();
                    if(!caller)return output;
                    auto& resumed_frame=frames.resolve(caller);
                    if(resumed_frame.object!=no_index) {
                        if(const auto* waiting=objects(resumed_frame).cleanup;waiting&&waiting->pending.waiting&&waiting->pending.callee==self) {
                            continuation_returned(caller,position,options);break;
                        }
                    }
                    if(trace_touched){clear_error_trace();trace_touched=false;}
                    if(resumed_frame.object!=no_index&&output.type_tag()==OBJECT_VALUE)
                        blocks::adopt_result(wrap(output),site(resumed_frame));
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
            bool resumes=false;
            // The walk only moves already-reserved records and captures every
            // cleanup error; a failure here would leave frames inconsistent.
            try{resumes=fail(mark,region_mark,position,options,resumed);}catch(...){std::terminate();}
            if(resumes)continue;
            regions.discard(region_mark);
            throw;
        }
    }
};

ScalarExecutor::ScalarExecutor(const ExecutableProgram& program,SlotPool& slots,WorkspaceBudget& workspace,const jit::Config& tier)
    :state_(std::make_unique<State>(program,slots,workspace,tier)) {}
ScalarExecutor::~ScalarExecutor()=default;
Value ScalarExecutor::invoke(std::uint32_t function,std::span<const Value> arguments,
        std::span<const SlotId> globals,std::uint64_t& remaining_steps,ExecutionPosition* position,ExecutionOptions options) {
    ExecutionPosition local;
    return state_->invoke(function,arguments,globals,remaining_steps,position?*position:local,options);
}
}
