#ifndef AZSCRIPT_EXTERN_LIBRARY_H
#define AZSCRIPT_EXTERN_LIBRARY_H
#include <string>
namespace azertian {
// Resolve a platform-neutral stem, verify its sibling signature, and call
// azscript_load_extern in the loaded image.
void load_named_extern_library(const std::string& name);
// Standalone runner only. Embedded hosts supply keys through the public API.
void load_standalone_trusted_keys();
}
#endif
