#include "config.h"
#include <charconv>
#include <cstdlib>
#include <stdexcept>
#include <string>

namespace azertian::jit {
Config parse_config(std::string_view text) {
    Config config;
    if(text.empty()||text=="off")return config;
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
    return parse_config(text?std::string_view(text):std::string_view{});
}
}
