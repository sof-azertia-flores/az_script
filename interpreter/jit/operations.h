#ifndef AZSCRIPT_JIT_OPERATIONS_H
#define AZSCRIPT_JIT_OPERATIONS_H

#include "shadow.h"
#include "../core/value_ops.h"
#include <optional>

namespace azertian::jit {
// value_ops on shadow values. nullopt when the operation fails or yields a
// value without a shadow (a string): native code then lets the VM run the
// instruction, which reports the failure itself. Int operations take a fast
// path that agrees with value_ops bit for bit; everything else calls it.
std::optional<Shadow> binary(BinaryOperation operation,const Shadow& left,const Shadow& right) noexcept;
std::optional<Shadow> unary(UnaryOperation operation,const Shadow& operand) noexcept;
std::optional<Shadow> convert(int target,const Shadow& operand) noexcept;
}
#endif
