#ifndef AZSCRIPT_CORE_TYPES_H
#define AZSCRIPT_CORE_TYPES_H

namespace azertian {
// Runtime type tags; the exec format and both engines use these numbers.
inline constexpr int INT_VALUE=0, STRING_VALUE=1, FLOAT_VALUE=2, DOUBLE_VALUE=3,
                     BOOLEAN_VALUE=4, VOID_VALUE=5, ANY_VALUE=6, ADDRESS_VALUE=7, OBJECT_VALUE=8;
// Lowercase source-level name of a tag, or "unknown"; used by every diagnostic.
const char* type_name(int type) noexcept;
// A generic type context: scalar (kind 0), address (1, 2), object or buffer (3, 4).
bool valid_context_kind(int abi,int kind) noexcept;
}
#endif
