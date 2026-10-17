#include "session.h"
#include "host_bridge.h"
#include "workspace.h"
#include "../library.h"
#include "../core/trace.h"
#include <algorithm>
#include <functional>
#include <stdexcept>

namespace azertian::vm {
const Value& HostValueAccess::read(const variable& source) noexcept {return source.value_;}
void HostValueAccess::assign(variable& destination,Value value) noexcept {
    destination.replace_value(std::move(value));
}

struct Session::Prepared final:HostBoundary {
    struct GlobalBinding {
        std::shared_ptr<variable> host;
        SlotId slot;
        bool reachable=false;
    };
    struct IdentityIndex {
        variable* host=nullptr;
        std::size_t index=0;
    };
    CompiledScript compiled;
    WorkspaceBudget budget;
    SlotPool slots;
    WorkspaceVector<SlotId> globals;
    WorkspaceVector<Value> arguments;
    WorkspaceVector<GlobalBinding> bindings;
    WorkspaceVector<IdentityIndex> identities,incoming;
    WorkspaceVector<SlotId> next_globals;
    ScalarExecutor executor;
    std::size_t active_invocations=0,minimum_globals=0;
    // Counts invocation starts; a runtime helper refreshes globals only if a
    // nested invocation (a destructor) started while it ran. Nested roots
    // share these slots, so script writes are already visible; the refresh
    // rebinds host-side changes and re-enables this invocation's publication.
    std::uint64_t invocations=0;
    bool export_allowed=false,publication_deferred=false;

    Prepared(CompiledScript source,std::size_t workspace_limit,const script& owner)
        :compiled(std::move(source)),budget(workspace_limit),slots(budget),
         globals(budget),arguments(budget),bindings(budget),identities(budget),
         incoming(budget),next_globals(budget),executor(compiled.program,slots,budget) {
        require_scalar_host_targets(compiled.program);
        for(const auto& function:compiled.program.functions)
            minimum_globals=std::max(minimum_globals,static_cast<std::size_t>(function.global_offset)+function.global_count);
        import_globals(owner);
    }
    static bool before(const IdentityIndex& left,const IdentityIndex& right) noexcept {
        return std::less<variable*>{}(left.host,right.host);
    }
    std::size_t find_identity(variable* host,std::size_t count) const noexcept {
        std::size_t first=0;
        while(first<count) {
            const auto middle=first+(count-first)/2;
            if(std::less<variable*>{}(identities[middle].host,host))first=middle+1;
            else count=middle;
        }
        return first;
    }
    static void validate_value(const std::shared_ptr<variable>& value) {
        if(!value)throw std::invalid_argument("Scalar VM global storage is unavailable");
        if(value->type_tag()==OBJECT_VALUE)
            throw std::invalid_argument("Scalar VM does not support object globals");
    }
    void validate_globals(const script& owner) const {
        if(!owner.baseEnv)throw std::runtime_error("Script is closed");
        for(const auto& value:owner.baseEnv->variables)validate_value(value);
        // A callback can replace a global wrapper while a suspended VM register
        // still references its old storage. That storage remains part of this
        // invocation and must also be refreshed or rejected before continuing.
        if(active_invocations)for(const auto& binding:bindings.span())validate_value(binding.host);
    }
    void import_globals(const script& owner) {
        // Once the host can mutate its wrappers it is authoritative. If any
        // validation/allocation below fails, an outer catch must not overwrite
        // those changes with stale VM values, especially an object payload.
        export_allowed=false;
        validate_globals(owner);
        const auto count=owner.baseEnv->variables.size();
        if(count<minimum_globals)
            throw std::logic_error("VM global storage differs from its prepared program");
        if(active_invocations&&count!=globals.size())
            throw std::logic_error("Cannot resize global storage during VM execution");

        incoming.clear();incoming.reserve(count);
        for(std::size_t i=0;i<count;++i)incoming.emplace_back(IdentityIndex{owner.baseEnv->variables[i].get(),i});
        std::sort(incoming.span().begin(),incoming.span().end(),before);
        next_globals.clear();next_globals.append_default(count);
        // The live global view is shared by suspended invocations. Its backing
        // can only grow before entering the outermost VM invocation.
        globals.reserve(count);
        const auto old_bindings=bindings.size(),old_identities=identities.size();
        std::size_t additional=0;
        for(std::size_t i=0;i<incoming.size();) {
            const auto host=incoming[i].host;
            const auto found=find_identity(host,old_identities);
            if(found==old_identities||identities[found].host!=host)++additional;
            do {++i;}while(i<incoming.size()&&incoming[i].host==host);
        }
        bindings.prepare_append(additional);identities.prepare_append(additional);
        struct Rollback {
            Prepared& state;std::size_t bindings,identities;bool committed=false;
            ~Rollback() {
                if(committed)return;
                for(std::size_t i=bindings;i<state.bindings.size();++i)state.slots.retire(state.bindings[i].slot);
                state.bindings.truncate(bindings);state.identities.truncate(identities);
            }
        } rollback{*this,old_bindings,old_identities};
        for(std::size_t i=0;i<incoming.size();) {
            const auto host=incoming[i].host;
            const auto found=find_identity(host,old_identities);
            std::size_t binding;
            if(found<old_identities&&identities[found].host==host)binding=identities[found].index;
            else {
                binding=bindings.size();
                auto wrapper=owner.baseEnv->variables[incoming[i].index];
                const auto slot=slots.emplace(HostValueAccess::read(*wrapper));
                bindings.emplace_back(GlobalBinding{std::move(wrapper),slot,false});
                identities.emplace_back(IdentityIndex{host,binding});
            }
            do {
                next_globals[incoming[i].index]=bindings[binding].slot;
                incoming[i].index=binding;
                ++i;
            }while(i<incoming.size()&&incoming[i].host==host);
        }
        // All potentially failing allocations have finished. Commit both the
        // identity map and values together; Value copies are non-throwing.
        for(auto& binding:bindings.span())binding.reachable=false;
        for(const auto& entry:incoming.span())bindings[entry.index].reachable=true;
        for(auto& binding:bindings.span())if(binding.reachable||active_invocations) {
            auto* value=slots.try_resolve(binding.slot);if(!value)std::terminate();
            *value=HostValueAccess::read(*binding.host);
        }
        if(globals.size()>count)globals.truncate(count);
        else globals.append_default(count-globals.size());
        for(std::size_t i=0;i<count;++i)globals[i]=next_globals[i];
        std::sort(identities.span().begin(),identities.span().end(),before);
        rollback.committed=true;export_allowed=true;
        if(!active_invocations)retire_unreachable(owner);
    }
    // Publish each storage identity exactly once. Removed host wrappers remain
    // alive while outer registers may still refer to their original SlotIds.
    void export_globals(script&) noexcept {
        if(!export_allowed)return;
        for(const auto& binding:bindings.span()) {
            const auto* value=slots.try_resolve(binding.slot);
            if(!value)std::terminate();
            HostValueAccess::assign(*binding.host,*value);
        }
        export_allowed=false;
    }
    void retire_unreachable(const script& owner) noexcept {
        if(active_invocations)return;
        for(auto& binding:bindings.span())binding.reachable=false;
        if(owner.baseEnv)for(const auto& wrapper:owner.baseEnv->variables) {
            const auto found=find_identity(wrapper.get(),identities.size());
            if(found<identities.size()&&identities[found].host==wrapper.get())
                bindings[identities[found].index].reachable=true;
        }
        std::size_t retained=0;
        for(std::size_t i=0;i<bindings.size();++i) {
            if(!bindings[i].reachable){slots.retire(bindings[i].slot);continue;}
            if(retained!=i)bindings[retained]=std::move(bindings[i]);
            ++retained;
        }
        bindings.truncate(retained);identities.clear();
        for(std::size_t i=0;i<bindings.size();++i)
            identities.emplace_back(IdentityIndex{bindings[i].host.get(),i});
        std::sort(identities.span().begin(),identities.span().end(),before);
    }
    WorkspaceBudget& workspace() noexcept override{return budget;}
    void publish_globals(script& owner) noexcept override{export_globals(owner);}
    void refresh_globals(const script& owner) override{import_globals(owner);}
    std::uint64_t defer_publication() noexcept override{publication_deferred=true;return invocations;}
    void end_deferred_publication(const script& owner,std::uint64_t mark) override {
        publication_deferred=false;
        if(invocations!=mark)import_globals(owner);
    }
};

Session::Session(script& owner,std::size_t workspace_limit)
    :owner_(owner),workspace_limit_(workspace_limit) {}
Session::~Session()=default;
void Session::prepare() {
    auto next=std::make_unique<Prepared>(compile_script(owner_),workspace_limit_,owner_);
    prepared_.swap(next);
}
const CompiledScript& Session::compiled() const {
    if(!prepared_)throw std::logic_error("VM program has not been prepared");
    return prepared_->compiled;
}
std::shared_ptr<variable> Session::invoke(std::uint32_t function,
        std::span<const std::shared_ptr<variable>> arguments,ExecutionPosition* position,
        const std::shared_ptr<environment>& caller) {
    if(!prepared_)throw std::logic_error("VM program has not been prepared");
    auto& state=*prepared_;
    ExecutionPosition local_position;
    if(!position)position=&local_position;
    *position={function};
    HostBridge callbacks(owner_,state.compiled.program,state);
    admission::accounting calls(owner_.active_calls,owner_.max_call_depth,
        owner_.remaining_steps,owner_.max_steps);
    try {
    // A runtime helper of a suspended invocation may be running this one
    // (a destructor): publish that invocation's globals first.
    if(state.publication_deferred){state.export_globals(owner_);state.publication_deferred=false;}
    ++state.invocations;
    state.import_globals(owner_);
    struct ActiveInvocation {
        Prepared& state;const script& owner;
        ActiveInvocation(Prepared& state,const script& owner):state(state),owner(owner){++state.active_invocations;}
        ~ActiveInvocation(){if(!--state.active_invocations)state.retire_unreachable(owner);}
    } active{state,owner_};
    const auto begin=state.arguments.size();
    struct RestoreArguments {
        WorkspaceVector<Value>& storage;std::size_t begin;
        ~RestoreArguments(){storage.truncate(begin);}
    } restore{state.arguments,begin};
    state.arguments.prepare_append(arguments.size());
    for(const auto& argument:arguments) {
        if(!argument)throw std::invalid_argument("Null argument pointer; use variable(nullptr)");
        state.arguments.emplace_back(HostValueAccess::read(*argument));
    }
    Value result;
    try {
        result=state.executor.invoke(function,state.arguments.span().subspan(begin),
            state.globals.span(),owner_.remaining_steps,position,
            ExecutionOptions{&calls,&callbacks,false,caller});
    } catch(...) {
        state.export_globals(owner_);throw;
    }
    state.export_globals(owner_);
    auto host_result=std::make_shared<variable>();
    HostValueAccess::assign(*host_result,std::move(result));
    return host_result;
    } catch(...) {
        if(!state.active_invocations)state.retire_unreachable(owner_);
        if(!callbacks.reported_error()&&position->function<state.compiled.program.functions.size()) {
            const auto& function=state.compiled.program.functions[position->function];
            trace::note_script_frame(function.function_id,function.debug?&*function.debug:nullptr,position->location);
        }
        throw;
    }
}
std::size_t Session::workspace_bytes() const noexcept {
    return prepared_?prepared_->budget.used_bytes():0;
}
std::size_t Session::workspace_peak_bytes() const noexcept {
    return prepared_?prepared_->budget.peak_bytes():0;
}
void Session::reset() noexcept {prepared_.reset();}
}
