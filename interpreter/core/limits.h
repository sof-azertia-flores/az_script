#ifndef AZSCRIPT_CORE_LIMITS_H
#define AZSCRIPT_CORE_LIMITS_H
#include <cstddef>

namespace azertian::limits {
// Slots of one function frame, object, buffer, heap (including reserved slot 0)
// and every count bounded by them in the exec format and both engines.
inline constexpr std::size_t max_slots=1048576;
}
#endif
