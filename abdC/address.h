#ifndef AZERTIAN_ADDRESS_H
#define AZERTIAN_ADDRESS_H

#include <compare>
#include <cstdint>

namespace azertian {
// A slot address is a distinct unsigned value, never an implicit script int32.
// Arithmetic is checked by the runtime before constructing another address.
struct address {
    std::uint64_t value;
    explicit constexpr address(std::uint64_t value = 0) noexcept : value(value) {}
    constexpr auto operator<=>(const address&) const = default;
};
}

#endif
