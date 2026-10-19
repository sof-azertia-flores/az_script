#ifndef AZSCRIPT_CORE_ERRORS_H
#define AZSCRIPT_CORE_ERRORS_H
#include <cstddef>
#include <cstdint>
#include <exception>
#include <string>
#include <vector>

namespace azertian {
// Frames are recorded innermost first. Debug names and positions are absent
// when the source was compiled without -g; function IDs are always available.
struct trace_frame {
    int function_id=0;
    bool native=false;
    std::string name,file;
    std::uint32_t line=0,column=0;
};
struct error_trace {
    std::vector<trace_frame> frames;
    std::size_t omitted_frames=0;
};
std::string function_id_text(int id);
const error_trace& last_error_trace() noexcept;
std::string format_error_trace(const error_trace& trace);
// Adds frames only for the exception type and message of the last failure on
// this thread. The original exception object and its dynamic type are retained.
std::string describe_error(const std::exception& error);
void clear_error_trace() noexcept;
namespace detail {struct trace_state;}
// Cleanup paths retain the first exception together with its trace, then
// discard traces from later cleanup failures before restoring the first one.
class pending_error {
    friend struct detail::trace_state;
    std::exception_ptr exception_;
    error_trace trace_;
public:
    pending_error()=default;
    static pending_error capture() noexcept;
    explicit operator bool() const noexcept;
    void capture_current() noexcept;
    [[noreturn]] void rethrow() const;
};
}
#endif
