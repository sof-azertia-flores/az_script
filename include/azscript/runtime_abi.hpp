#pragma once
#include <cstdint>
#include <stdexcept>

// C++ runtime layout/API identity, independent of exec and JNI snapshot formats.
// A breaking runtime change must use a new symbol and shared-library identity.
#define AZSCRIPT_RUNTIME_ABI 1

extern "C" std::uint32_t azscript_runtime_abi() noexcept;
extern "C" void azscript_require_runtime_abi_1() noexcept;

namespace azertian {
inline constexpr std::uint32_t RUNTIME_ABI_VERSION=AZSCRIPT_RUNTIME_ABI;

namespace detail {
inline void require_runtime_abi_1() {
    // This versioned reference also rejects stale libraries during static linking.
    azscript_require_runtime_abi_1();
    if(azscript_runtime_abi()!=RUNTIME_ABI_VERSION)
        throw std::runtime_error("AzScript runtime ABI mismatch: expected ABI 1");
}

// Keep the guard's identity ABI-specific so a different DSO cannot coalesce it
// with another ABI's inline variable and skip that ABI's link/runtime check.
struct runtime_abi_guard_1 {
    runtime_abi_guard_1(){require_runtime_abi_1();}
};
inline const runtime_abi_guard_1 runtime_abi_check_1{};
}

inline void require_runtime_abi() {detail::require_runtime_abi_1();}
}
