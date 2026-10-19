#include <azscript/runtime_abi.hpp>

extern "C" std::uint32_t azscript_runtime_abi() noexcept {
    return AZSCRIPT_RUNTIME_ABI;
}
extern "C" void azscript_require_runtime_abi_1() noexcept {}
