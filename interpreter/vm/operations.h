#ifndef AZSCRIPT_VM_OPERATIONS_H
#define AZSCRIPT_VM_OPERATIONS_H
#include "program.h"

namespace azertian::vm {
// Pure value operations: callers own evaluation order, budget checks, source
// locations, argument snapshots and object lifetime. Scalar operations do not
// allocate variable wrappers or numeric payloads.
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
