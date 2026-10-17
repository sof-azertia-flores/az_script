#pragma once
// Header for a dynamic library loaded by load_extern_library.
// The library links the shared AzScript runtime and, from azscript_load_extern,
// registers host functions with the same API an embedded C++ host uses:
// azertian::executor, azertian::function, azertian::variable, and
// azertian::registerExecutor / azertian::unregisterExecutor.
#include "runtime.hpp"

#if defined(_WIN32)
#define AZSCRIPT_EXTERN_EXPORT __declspec(dllexport)
#else
#define AZSCRIPT_EXTERN_EXPORT __attribute__((visibility("default")))
#endif

// Define this macro once in the plugin. The loader checks the declared ABI
// after signature verification and mapping, before calling the registration
// entry point. Rebuild and re-sign plugins when the runtime ABI changes.
#define AZSCRIPT_EXTERN_ENTRY \
    extern "C" AZSCRIPT_EXTERN_EXPORT std::uint32_t azscript_extern_runtime_abi() noexcept { \
        return AZSCRIPT_RUNTIME_ABI; \
    } \
    extern "C" AZSCRIPT_EXTERN_EXPORT void azscript_load_extern()

extern "C" AZSCRIPT_EXTERN_EXPORT void azscript_load_extern();
