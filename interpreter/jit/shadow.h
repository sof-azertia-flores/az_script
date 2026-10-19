#ifndef AZSCRIPT_JIT_SHADOW_H
#define AZSCRIPT_JIT_SHADOW_H

#include "../core/value.h"
#include "../core/types.h"
#include <bit>
#include <cstdint>
#include <optional>

namespace azertian::jit {
// A scalar value held by native code instead of VM memory: its bits and its
// public type tag. absent means VM memory holds the authoritative value (a
// reference register, an unbound local, a string), so exits write nothing.
inline constexpr std::uint8_t absent=0xff;
struct Shadow {
    std::uint64_t bits=0;
    std::uint8_t tag=absent;
    bool operator==(const Shadow&) const=default;
};
// Ints and floats keep their bit pattern in the low 32 bits; booleans are 0/1.
inline std::optional<Shadow> shadow_of(const Value& value) noexcept {
    switch(value.type_tag()) {
    case INT_VALUE:return Shadow{std::bit_cast<std::uint32_t>(value.get<int>()),INT_VALUE};
    case FLOAT_VALUE:return Shadow{std::bit_cast<std::uint32_t>(value.get<float>()),FLOAT_VALUE};
    case DOUBLE_VALUE:return Shadow{std::bit_cast<std::uint64_t>(value.get<double>()),DOUBLE_VALUE};
    case BOOLEAN_VALUE:return Shadow{value.get<bool>()?1u:0u,BOOLEAN_VALUE};
    case ADDRESS_VALUE:return Shadow{value.get<address>().value,ADDRESS_VALUE};
    case VOID_VALUE:return Shadow{0,VOID_VALUE};
    default:return std::nullopt;
    }
}
inline Value value_of(const Shadow& shadow) noexcept {
    switch(shadow.tag) {
    case INT_VALUE:return Value(std::bit_cast<int>(static_cast<std::uint32_t>(shadow.bits)));
    case FLOAT_VALUE:return Value(std::bit_cast<float>(static_cast<std::uint32_t>(shadow.bits)));
    case DOUBLE_VALUE:return Value(std::bit_cast<double>(shadow.bits));
    case BOOLEAN_VALUE:return Value(shadow.bits!=0);
    case ADDRESS_VALUE:return Value(address{shadow.bits});
    default:return Value{};
    }
}
}
#endif
