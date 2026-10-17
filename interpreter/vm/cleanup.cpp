#include "cleanup.h"
#include <exception>
#include <stdexcept>
#include <type_traits>

namespace azertian::vm {
static_assert(std::is_nothrow_move_constructible_v<pending_error>);
static_assert(std::is_nothrow_move_assignable_v<pending_error>);
void CompilerCleanupStack::SavedFlow::save(const FrameRecord& frame) noexcept {
    returned=frame.returned;broken=frame.broken;continued=frame.continued;result=frame.result;
    location=frame.location;return_location=frame.return_location;
}
void CompilerCleanupStack::SavedFlow::restore(FrameRecord& frame) const noexcept {
    frame.returned=returned;frame.broken=broken;frame.continued=continued;frame.result=result;
    frame.location=location;frame.return_location=return_location;
}
CompilerCleanupStack::CompilerCleanupStack(WorkspaceBudget& workspace,std::pmr::memory_resource* resource)
    :records_(workspace,std::numeric_limits<std::size_t>::max()/sizeof(Record),resource) {}
CompilerCleanupStack::Record& CompilerCleanupStack::current(FrameId frame) {
    if(records_.empty()||records_.back().frame!=frame)throw std::logic_error("VM cleanup continuation belongs to another frame");
    return records_.back();
}
CleanupTransition CompilerCleanupStack::transition(const Record& record,CleanupTransition::Kind kind,ProgramCounter pc) const noexcept {
    CleanupTransition result;result.kind=kind;result.frame=record.frame;result.pc=pc;result.watermark=record.watermark;return result;
}
void CompilerCleanupStack::enter(FrameId frame,const CleanupRegion& region,CleanupWatermark watermark) {
    if(!frame)throw std::logic_error("VM cleanup requires a live frame identity");
    if(records_.size()==records_.capacity())throw std::logic_error("VM cleanup continuation capacity was not prepared");
    Record record;record.frame=frame;record.region=region;record.watermark=watermark;
    records_.emplace_back(std::move(record));
}
CleanupTransition CompilerCleanupStack::body_finished(FrameId frame) {
    auto& record=current(frame);
    if(record.phase!=Phase::body)throw std::logic_error("VM cleanup body completed in the wrong phase");
    record.phase=Phase::unwind_body;return transition(record,CleanupTransition::Kind::unwind);
}
CleanupTransition CompilerCleanupStack::finalizer_finished(FrameId frame) {
    auto& record=current(frame);
    if(record.phase!=Phase::finalizer)throw std::logic_error("VM cleanup finalizer completed in the wrong phase");
    record.phase=Phase::unwind_finalizer;return transition(record,CleanupTransition::Kind::unwind);
}
CleanupTransition CompilerCleanupStack::failed(FrameId frame,const FrameRecord& state) {
    if(!std::current_exception())throw std::logic_error("VM cleanup failure reported without an active exception");
    auto& record=current(frame);const bool first=!record.failure;
    record.failure.capture_current();
    if(first) {
        record.first_error_location=state.location;
        if(record.phase==Phase::finalizer||record.phase==Phase::unwind_finalizer)record.saved.location=state.location;
    }
    if(record.phase==Phase::body)record.phase=Phase::unwind_body;
    else if(record.phase==Phase::finalizer)record.phase=Phase::unwind_finalizer;
    return transition(record,CleanupTransition::Kind::unwind);
}
CleanupTransition CompilerCleanupStack::unwound(FrameId frame,FrameRecord& state) {
    auto& record=current(frame);
    if(record.phase==Phase::unwind_body) {
        if(!record.region.on_error||record.failure) {
            record.saved.save(state);
            if(record.failure)record.saved.location=record.first_error_location;
            state.returned=false;state.broken=false;state.continued=false;
            record.phase=Phase::finalizer;return transition(record,CleanupTransition::Kind::finalizer,record.region.finalizer);
        }
    } else if(record.phase==Phase::unwind_finalizer)record.saved.restore(state);
    else throw std::logic_error("VM cleanup unwind completed in the wrong phase");
    auto result=transition(record,CleanupTransition::Kind::resume,record.region.after);
    result.failure=std::move(record.failure);records_.pop_back();return result;
}

CleanupActions::CleanupActions(WorkspaceBudget& workspace,std::pmr::memory_resource* resource)
    :actions_(workspace,std::numeric_limits<std::size_t>::max()/sizeof(Action),resource),
     sessions_(workspace,std::numeric_limits<std::size_t>::max()/sizeof(Session),resource) {}
CleanupActions::~CleanupActions() {
    // Emergency VM teardown releases storage only. Normal scope/frame exit
    // must first poll every action and handle any pending script request.
    while(!actions_.empty())finish_action();
}
CleanupActions::Session& CleanupActions::session() {
    if(sessions_.empty())throw std::logic_error("VM cleanup has no active unwind");
    return sessions_.back();
}
CleanupActions::Action& CleanupActions::waiting_action() {
    const auto& current=session();
    if(!current.waiting||current.waiting_index+1!=actions_.size())throw std::logic_error("VM cleanup script continuation is not current");
    return actions_.back();
}
void CleanupActions::finish_action() noexcept {
    auto& action=actions_.back();action.driver->release(action.state);actions_.pop_back();
}
void CleanupActions::action_failed() noexcept {
    auto& current=sessions_.back();current.failure.capture_current();
    auto& action=actions_.back();
    if(!action.driver->failed(action.state))finish_action();
}
void CleanupActions::register_action(CleanupDriver& driver,CleanupActionState state) {
    if(actions_.size()==actions_.capacity())throw std::logic_error("VM cleanup action capacity was not prepared");
    if(!sessions_.empty()&&!sessions_.back().waiting)throw std::logic_error("VM cleanup driver cannot publish new ownership while polling");
    actions_.emplace_back(Action{&driver,std::move(state)});
}
void CleanupActions::begin_unwind(std::size_t mark,pending_error&& failure) {
    if(mark>actions_.size())throw std::logic_error("VM cleanup watermark is outside action storage");
    if(!sessions_.empty()&&(!sessions_.back().waiting||mark<=sessions_.back().waiting_index))
        throw std::logic_error("VM nested cleanup crosses a suspended continuation");
    if(sessions_.size()==sessions_.capacity())throw std::logic_error("VM cleanup unwind capacity was not prepared");
    Session current;current.mark=mark;current.failure=std::move(failure);sessions_.emplace_back(std::move(current));
}
CleanupPoll CleanupActions::poll() {
    if(session().waiting)throw std::logic_error("VM cleanup is awaiting a script result");
    while(actions_.size()>session().mark) {
        auto& action=actions_.back();
        try {
            auto step=action.driver->advance(action.state);
            if(step.kind==CleanupActionStep::Kind::finished){finish_action();continue;}
            if(step.kind!=CleanupActionStep::Kind::script||step.call.argument_count>step.call.arguments.size())
                throw std::logic_error("VM cleanup driver returned an invalid script request");
            auto& current=session();current.waiting=true;current.waiting_index=actions_.size()-1;
            CleanupPoll result;result.kind=CleanupPoll::Kind::script;result.frame=action.state.frame;
            result.call=std::move(step.call);return result;
        } catch(...) {action_failed();}
    }
    CleanupPoll result;result.failure=std::move(session().failure);sessions_.pop_back();return result;
}
void CleanupActions::returned(const Value& result) {
    auto& action=waiting_action();session().waiting=false;
    try {action.driver->returned(action.state,result);}catch(...) {action_failed();}
}
void CleanupActions::failed() {
    waiting_action();
    if(!std::current_exception())throw std::logic_error("VM cleanup script failure reported without an active exception");
    session().waiting=false;action_failed();
}
}
