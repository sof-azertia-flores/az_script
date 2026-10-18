#include "trace.h"
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <typeinfo>
#include <utility>
#include <cstdio>

namespace azertian {
namespace detail {
struct trace_state {
    error_trace active,published;
    const std::type_info* published_type=nullptr;
    // Keep exception ownership inside pending_error. Equality works for every
    // C++ exception type and retaining it prevents allocator-address reuse.
    pending_error published_exception;
    std::string published_message;
    std::size_t entries=0,sequence=0,published_sequence=0;
    trace_state* next=nullptr;
    void remember_exception() noexcept {published_exception.exception_=std::current_exception();}
    bool matches_current_exception() const noexcept {return published_exception.exception_==std::current_exception();}
    void forget_exception() noexcept {published_exception.exception_=nullptr;}
};
}
namespace {
using detail::trace_state;
// A trivial TLS pointer remains usable during host and plugin teardown. In
// particular, no TLS vector/string destructor races later runtime cleanup.
thread_local trace_state* thread_trace=nullptr;
struct trace_registry {
    std::mutex mutex;
    trace_state* first=nullptr;
};
trace_state* trace_storage() noexcept {
    // Process ownership also retains states from terminated threads. Keeping
    // the registry itself alive avoids static destruction-order dependencies.
    static trace_registry* registry=new(std::nothrow) trace_registry;
    if(!thread_trace&&registry) {
        auto state=new(std::nothrow) trace_state;if(!state)return nullptr;
        try {
            std::lock_guard<std::mutex> lock(registry->mutex);
            state->next=registry->first;registry->first=state;thread_trace=state;
        } catch(...) {delete state;}
    }
    return thread_trace;
}
void publish_trace(const std::exception* error,bool move_outermost=true) noexcept {
    auto state=thread_trace;if(!state)return;
    try {
        std::string message=error&&error->what()?error->what():"";
        error_trace trace;
        if(move_outermost&&state->entries<=1){trace=std::move(state->active);state->active={};}
        else trace=state->active;
        state->published=std::move(trace);
        state->published_message=std::move(message);
        state->published_type=error?&typeid(*error):nullptr;
        state->remember_exception();state->published_sequence=state->sequence;
    } catch(...) {
        state->published={};state->published_message.clear();state->published_type=nullptr;state->forget_exception();
    }
}
}
void trace::note_frame(trace_frame frame) noexcept {
    auto state=trace_storage();if(!state)return;
    try {
        if(state->active.frames.size()<64)state->active.frames.push_back(std::move(frame));
        else ++state->active.omitted_frames;
    } catch(...) {++state->active.omitted_frames;}
}
std::string function_id_text(int id) {
    char text[16];std::snprintf(text,sizeof text,"0x%08x",static_cast<unsigned>(static_cast<std::uint32_t>(id)));
    return text;
}
const error_trace& last_error_trace() noexcept {
    static const error_trace empty;
    return thread_trace?thread_trace->published:empty;
}
std::string format_error_trace(const error_trace& trace) {
    std::string result;
    for(const auto& frame:trace.frames) {
        if(!result.empty())result+='\n';
        result+="  at ";
        if(frame.native)result+="native function "+function_id_text(frame.function_id);
        else if(frame.name.empty())result+="function "+function_id_text(frame.function_id);
        else result+=frame.name;
        if(!frame.native&&!frame.file.empty()&&frame.line&&frame.column)
            result+=" ("+frame.file+":"+std::to_string(frame.line)+":"+std::to_string(frame.column)+")";
    }
    if(trace.omitted_frames) {
        if(!result.empty())result+='\n';
        result+="  ... "+std::to_string(trace.omitted_frames)+" more frames";
    }
    return result;
}
std::string describe_error(const std::exception& error) {
    std::string result=error.what()?error.what():"";
    if(thread_trace&&thread_trace->published_type&&*thread_trace->published_type==typeid(error)&&
       thread_trace->published_message==result) {
        auto trace=format_error_trace(thread_trace->published);
        if(!trace.empty())result+='\n'+trace;
    }
    return result;
}
void clear_error_trace() noexcept {
    if(thread_trace) {
        thread_trace->active={};thread_trace->published={};
        thread_trace->published_message.clear();thread_trace->published_type=nullptr;thread_trace->forget_exception();
    }
}
pending_error pending_error::capture() noexcept {
    pending_error result;result.capture_current();return result;
}
pending_error::operator bool() const noexcept {return static_cast<bool>(exception_);}
void pending_error::capture_current() noexcept {
    if(!exception_) {
        exception_=std::current_exception();
        if(exception_&&thread_trace) {
            trace_=std::move(thread_trace->active);
            // A direct host cleanup can catch an invocation after its outer
            // entry moved the active trace into the published record.
            if(trace_.frames.empty()&&!trace_.omitted_frames&&
               thread_trace->published_sequence==thread_trace->sequence) {
                if(thread_trace->published_exception.exception_==exception_)
                    try {trace_=thread_trace->published;}catch(...) {}
            }
        }
    }
    trace::discard_active();
}
[[noreturn]] void pending_error::rethrow() const {
    if(thread_trace) {
        try {thread_trace->active=trace_;}catch(...){thread_trace->active={};}
    }
    if(!exception_)throw std::logic_error("No pending error");
    try {std::rethrow_exception(exception_);}
    catch(const std::exception& error) {
        publish_trace(&error,!thread_trace||!thread_trace->entries);
        throw;
    } catch(...) {
        publish_trace(nullptr,!thread_trace||!thread_trace->entries);
        throw;
    }
}
trace::entry_scope::entry_scope() noexcept:state(trace_storage()) {
    if(state){++state->entries;++state->sequence;state->active={};}
}
trace::entry_scope::~entry_scope() noexcept {
    if(state){if(!failed)clear_error_trace();--state->entries;}
}
void trace::entry_scope::publish(const std::exception* error) noexcept {publish_trace(error);}
trace::open_entry trace::open_entry::begin() noexcept {
    open_entry result;result.state=trace_storage();
    if(result.state){++result.state->entries;++result.state->sequence;result.state->active={};}
    return result;
}
void trace::open_entry::succeed() noexcept {
    if(state){clear_error_trace();--state->entries;}
}
void trace::open_entry::fail() noexcept {
    if(state){publish_current();--state->entries;}
}
void trace::publish_current() noexcept {
    try {throw;}
    catch(const std::exception& error){publish_trace(&error);}
    catch(...){publish_trace(nullptr);}
}
void trace::discard_active() noexcept {
    if(thread_trace)thread_trace->active={};
}
bool trace::current_is_published() noexcept {
    return thread_trace&&thread_trace->matches_current_exception();
}
void trace::note_script_frame(int function_id,const function_debug* debug,std::uint32_t location) noexcept {
    try {
        trace_frame result;result.function_id=function_id;
        if(debug) {
            result.name=debug->name;
            const auto position=location&&location<=debug->locations.size()?debug->locations[location-1]:debug->header;
            if(position.line&&position.column&&debug->files&&position.file<debug->files->size()) {
                result.file=(*debug->files)[position.file];result.line=position.line;result.column=position.column;
            }
        }
        note_frame(std::move(result));
    } catch(...) {if(auto state=trace_storage())++state->active.omitted_frames;}
}
}
