#ifndef AZSCRIPT_JIT_CONFIG_H
#define AZSCRIPT_JIT_CONFIG_H

#include <cstdint>
#include <string_view>

namespace azertian::jit {
// How a VM session uses the JIT tier. Off runs the interpreter alone. Probe
// is test-only: it moves the points where the dispatch loop consults the tier
// pseudo-randomly and must not change any observation.
enum class Mode : std::uint8_t {off,probe};
struct Config {
    Mode mode=Mode::off;
    std::uint64_t seed=1;
    bool operator==(const Config&) const=default;
};
// "off", "probe" or "probe:<seed>"; an empty text is off. Anything else is
// rejected rather than silently running without the tier.
Config parse_config(std::string_view text);
// AZSCRIPT_JIT, read when a VM session is created.
Config environment_config();
}
#endif
