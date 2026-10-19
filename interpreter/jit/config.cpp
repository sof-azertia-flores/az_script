#include "config.h"
#include <charconv>
#include <cstdlib>
#include <stdexcept>
#include <string>

namespace azertian::jit {
Backend parse_backend(std::string_view text) {
    if(text.empty()||text=="interp")return Backend::interp;
    if(text=="llvm")return Backend::llvm;
    throw std::invalid_argument("Unknown AZSCRIPT_JIT_BACKEND: "+std::string(text));
}
Config parse_config(std::string_view text) {
    Config config;
    if(text.empty()||text=="off")return config;
    // A compiling mode may name its backend: eager+llvm, stress+interp.
    if(const auto plus=text.find('+');plus!=std::string_view::npos) {
        auto result=parse_config(text.substr(0,plus));
        if(result.mode!=Mode::eager&&result.mode!=Mode::stress)throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
        result.backend=parse_backend(text.substr(plus+1));
        if(text.substr(plus+1).empty())throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
        return result;
    }
    if(text=="eager"){config.mode=Mode::eager;return config;}
    if(text=="stress"){config.mode=Mode::stress;return config;}
    const auto separator=text.find(':');
    const auto mode=text.substr(0,separator);
    if(mode!="probe")throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
    config.mode=Mode::probe;
    if(separator!=std::string_view::npos) {
        const auto seed=text.substr(separator+1);
        const auto [end,error]=std::from_chars(seed.data(),seed.data()+seed.size(),config.seed);
        if(seed.empty()||error!=std::errc{}||end!=seed.data()+seed.size())
            throw std::invalid_argument("Invalid AZSCRIPT_JIT seed: "+std::string(text));
    }
    return config;
}
Config environment_config() {
    const char* text=std::getenv("AZSCRIPT_JIT");
    const std::string_view mode=text?std::string_view(text):std::string_view{};
    auto config=parse_config(mode);
    // AZSCRIPT_JIT_BACKEND chooses the backend unless the mode names one.
    const char* backend=std::getenv("AZSCRIPT_JIT_BACKEND");
    if(mode.find('+')==std::string_view::npos)config.backend=parse_backend(backend?std::string_view(backend):std::string_view{});
    return config;
}
}
