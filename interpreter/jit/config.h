#ifndef AZSCRIPT_JIT_CONFIG_H
#define AZSCRIPT_JIT_CONFIG_H

#include <cstdint>
#include <string_view>

namespace azertian::jit {
// How a VM session uses the JIT tier. Off runs the interpreter alone. The
// others are test modes until a production policy exists: probe moves the
// points where the dispatch loop consults the tier pseudo-randomly, eager
// runs every eligible function natively from its first entry, and stress
// does so one instruction per entry. None may change any observation.
enum class Mode : std::uint8_t {off,probe,eager,stress};
struct Config {
    Mode mode=Mode::off;
    std::uint64_t seed=1;
    bool operator==(const Config&) const=default;
};
// "off", "probe", "probe:<seed>", "eager" or "stress"; an empty text is
// off. Anything else is rejected rather than silently running without the
// tier.
Config parse_config(std::string_view text);
// AZSCRIPT_JIT, read when a VM session is created.
Config environment_config();
}
#endif
