#ifndef AZSCRIPT_CORE_TRACE_H
#define AZSCRIPT_CORE_TRACE_H
#include "debug.h"
#include "errors.h"
#include <cstdint>
#include <exception>

// Per-thread error-trace bookkeeping shared by both engines. Not installed.
namespace azertian::trace {
// One public, script or host entry. On entry the active trace is cleared; on
// success the published trace is cleared; on failure the active trace is
// published (moved by the outermost entry, copied by nested ones).
class entry_scope {
    detail::trace_state* state;
    bool failed=false;
public:
    entry_scope() noexcept;
    ~entry_scope() noexcept;
    entry_scope(const entry_scope&)=delete;
    entry_scope& operator=(const entry_scope&)=delete;
    template<class Body> decltype(auto) run(Body body) {
        try {return body();}
        catch(const std::exception& error){failed=true;publish(&error);throw;}
        catch(...){failed=true;publish(nullptr);throw;}
    }
    static void publish(const std::exception* error) noexcept;
};
void discard_active() noexcept;
// True when the exception being handled is the one already published.
bool current_is_published() noexcept;
void note_frame(trace_frame frame) noexcept;
// The one script-frame recorder: the position is debug->locations[location-1],
// or the header when location is 0 or outside the record.
void note_script_frame(int function_id,const function_debug* debug,std::uint32_t location) noexcept;
}
#endif
