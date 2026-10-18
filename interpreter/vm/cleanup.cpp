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
CleanupTransition CompilerCleanupStack::failed(FrameId frame,const FrameRecord& state,const ExecutionPosition* position) {
    if(!std::current_exception())throw std::logic_error("VM cleanup failure reported without an active exception");
    auto& record=current(frame);const bool first=!record.failure;
    record.failure.capture_current();
    if(first) {
        record.first_error_location=state.location;
        if(position)record.first_error_position=*position;
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
    result.failure=std::move(record.failure);result.position=record.first_error_position;
    records_.pop_back();return result;
}
}
