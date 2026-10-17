#include "types.h"

namespace azertian {
const char* type_name(int type) noexcept {
    switch(type) {
    case INT_VALUE:return "int";
    case STRING_VALUE:return "string";
    case FLOAT_VALUE:return "float";
    case DOUBLE_VALUE:return "double";
    case BOOLEAN_VALUE:return "boolean";
    case VOID_VALUE:return "void";
    case ANY_VALUE:return "any";
    case ADDRESS_VALUE:return "address";
    case OBJECT_VALUE:return "object";
    default:return "unknown";
    }
}
}
