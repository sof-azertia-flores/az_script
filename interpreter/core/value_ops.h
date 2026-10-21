#ifndef AZSCRIPT_CORE_VALUE_OPS_H
#define AZSCRIPT_CORE_VALUE_OPS_H
#include "value.h"
#include <cstdint>
#include <string>

namespace azertian {
// Operations of the exec format's unary, binary and bitwise opcodes.
enum class UnaryOperation : std::uint32_t { logical_not, negate };
// The wrapping operations (exec v11) compute int32 results in two's
// complement without an overflow check; other operands behave as add,
// subtract and multiply.
enum class BinaryOperation : std::uint32_t {
    add, subtract, multiply, divide, remainder, greater, less, equal,
    not_equal, greater_equal, less_equal, bit_and, bit_or, bit_xor,
    shift_left, shift_right, unsigned_shift_right,
    wrapping_add, wrapping_subtract, wrapping_multiply
};
}
// The runtime's one implementation of value arithmetic, comparison,
// conversion, return conversion and formatting, shared by both engines with
// identical results and messages. Callers own evaluation order, budget checks,
// source locations, argument snapshots and object lifetime. Scalar operations
// do not allocate variable wrappers or numeric payloads.
namespace azertian::value_ops {
bool numeric(const Value& value) noexcept;
double number(const Value& value);
bool truth(const Value& value);
std::string to_string(const Value& value);
Value unary(UnaryOperation operation,const Value& value);
Value binary(BinaryOperation operation,const Value& left,const Value& right);
Value convert(const Value& value,int target);
Value convert_return(const Value& value,int target);
}
#endif
