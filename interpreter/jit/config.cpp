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
namespace {
std::uint64_t number(std::string_view digits,std::string_view text,const char* what) {
    std::uint64_t value=0;
    const auto [end,error]=std::from_chars(digits.data(),digits.data()+digits.size(),value);
    if(digits.empty()||error!=std::errc{}||end!=digits.data()+digits.size())
        throw std::invalid_argument(std::string("Invalid AZSCRIPT_JIT ")+what+": "+std::string(text));
    return value;
}
}
Config parse_config(std::string_view text) {
    Config config;
    if(text.empty()||text=="off")return config;
    // A compiling mode may name its backend: eager+llvm, on:10+interp.
    if(const auto plus=text.find('+');plus!=std::string_view::npos) {
        auto result=parse_config(text.substr(0,plus));
        if(result.mode==Mode::off||result.mode==Mode::probe||text.substr(plus+1).empty())
            throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
        result.backend=parse_backend(text.substr(plus+1));
        return result;
    }
    const auto separator=text.find(':');
    const auto mode=text.substr(0,separator);
    const auto argument=separator==std::string_view::npos?std::string_view{}:text.substr(separator+1);
    if(mode=="eager"||mode=="stress") {
        if(separator!=std::string_view::npos)throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
        config.mode=mode=="eager"?Mode::eager:Mode::stress;return config;
    }
    if(mode=="probe") {
        config.mode=Mode::probe;
        if(separator!=std::string_view::npos)config.seed=number(argument,text,"seed");
        return config;
    }
    if(mode=="on"||mode=="require") {
        // The production policy compiles with the LLVM module by default.
        config.mode=mode=="on"?Mode::on:Mode::require;config.backend=Backend::llvm;
        if(separator!=std::string_view::npos) {
            config.threshold=number(argument,text,"threshold");
            if(!config.threshold)throw std::invalid_argument("Invalid AZSCRIPT_JIT threshold: "+std::string(text));
        }
        return config;
    }
    throw std::invalid_argument("Unknown AZSCRIPT_JIT mode: "+std::string(text));
}
Config environment_config() {
    const char* text=std::getenv("AZSCRIPT_JIT");
    const std::string_view mode=text?std::string_view(text):std::string_view{};
    auto config=parse_config(mode);
    // AZSCRIPT_JIT_BACKEND chooses the backend unless the mode names one.
    const char* backend=std::getenv("AZSCRIPT_JIT_BACKEND");
    if(mode.find('+')==std::string_view::npos&&backend&&*backend)config.backend=parse_backend(backend);
    return config;
}
}
