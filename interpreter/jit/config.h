#ifndef AZSCRIPT_JIT_CONFIG_H
#define AZSCRIPT_JIT_CONFIG_H

#include <cstdint>
#include <string_view>

namespace azertian::jit {
// How a VM session uses the JIT tier. Off runs the interpreter alone. On
// compiles a function once its entries and loop back-edges reach the
// threshold and runs it natively from its next entry or loop head; without
// the requested backend it leaves the VM unchanged, where require fails.
// The test modes: probe moves the points where the dispatch loop consults
// the tier pseudo-randomly, eager runs every eligible function natively from
// its first entry, stress does so one instruction per entry. None may change
// any observation.
enum class Mode : std::uint8_t {off,probe,eager,stress,on,require};
// Interp runs the MIR portably; llvm runs code from the JIT module and makes
// a session fail to prepare when the module cannot be loaded.
enum class Backend : std::uint8_t {interp,llvm};
struct Config {
    Mode mode=Mode::off;
    std::uint64_t seed=1;
    Backend backend=Backend::interp;
    std::uint64_t threshold=1000;
    bool operator==(const Config&) const=default;
};
// "off", "probe[:<seed>]", "eager", "stress", "on[:<threshold>]" or
// "require[:<threshold>]", the compiling modes optionally with "+interp" or
// "+llvm" (on and require use llvm otherwise); an empty text is off.
// Anything else is rejected rather than silently running without the tier.
Config parse_config(std::string_view text);
// "interp" (or empty) or "llvm".
Backend parse_backend(std::string_view text);
// AZSCRIPT_JIT and AZSCRIPT_JIT_BACKEND, read when a VM session is created.
Config environment_config();
}
#endif
