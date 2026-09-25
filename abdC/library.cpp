#include "library.h"
#include "azertianBinaryDataValues.h"

#include <bit>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace azertian {
namespace {
static_assert(sizeof(int) == 4, "ABD requires 32-bit int");
// Bound recursion for corrupt/cyclic data, including callers constructing their
// own object graphs. Thread-local state keeps independent threads separate.
thread_local std::size_t depth = 0;
struct DepthGuard {
    DepthGuard() {
        if (depth >= 128) throw std::invalid_argument("ABD nesting exceeds 128 levels or contains a cycle");
        ++depth;
    }
    ~DepthGuard() { --depth; }
};
void validPayload(const AbdValue& v) {
    if (v.size < 0 || (v.size != 0 && !v.data))
        throw std::invalid_argument("invalid ABD payload");
}
std::shared_ptr<AbdMapValue> decode(const std::shared_ptr<AbdValue>& type,
                                    const std::shared_ptr<AbdValue>& value) {
    if (!type || !value || type->size != 4 || !type->data)
        throw std::invalid_argument("ABD type must contain four bytes");
    validPayload(*value);
    const int code = b2i(type->data);
    switch (code) {
    case 1: return std::make_shared<StringAbdValue>(value);
    case 2: return std::make_shared<AbdMap>(std::make_shared<AbdStack>(value));
    case 3: return std::make_shared<IntAbdValue>(value);
    case 0xce1066: return std::make_shared<DoubleAbdValue>(value);
    case 0xce867: return std::make_shared<FloatAbdValue>(value);
    case 0xce2009: // Legacy BigInteger/raw-byte tag; bytes are preserved.
    case 0xce200a: return std::make_shared<ByteArrayValue>(value, code);
    case 0x0d00: return std::make_shared<BoolAbdValue>(value);
    case 0xad: return std::make_shared<AbdArray>(std::make_shared<AbdStack>(value));
    default: throw std::invalid_argument("unknown ABD type: " + std::to_string(code));
    }
}
}

void i2b(int i, unsigned char* n) {
    if (!n) throw std::invalid_argument("null integer buffer");
    const auto bits = std::bit_cast<std::uint32_t>(i);
    for (int j = 0; j < 4; ++j) n[j] = static_cast<unsigned char>(bits >> (8 * j));
}
int b2i(const unsigned char* b) {
    if (!b) throw std::invalid_argument("null integer buffer");
    const std::uint32_t bits = std::uint32_t(b[0]) | (std::uint32_t(b[1]) << 8)
        | (std::uint32_t(b[2]) << 16) | (std::uint32_t(b[3]) << 24);
    return std::bit_cast<int>(bits);
}
std::shared_ptr<unsigned char> i2b(int i) {
    std::shared_ptr<unsigned char> result(new unsigned char[4], std::default_delete<unsigned char[]>());
    i2b(i, result.get());
    return result;
}

AbdValue::AbdValue(const unsigned char* dat, int length) {
    if (length < 0 || (length != 0 && !dat)) throw std::invalid_argument("invalid ABD payload length or pointer");
    auto buffer = std::make_unique<unsigned char[]>(static_cast<std::size_t>(length));
    if (length) std::memcpy(buffer.get(), dat, static_cast<std::size_t>(length));
    data = buffer.release();
    size = length;
}
AbdValue::AbdValue(unsigned char* abd) : AbdValue(abd ? abd + 4 : nullptr, b2i(abd)) {}
AbdValue::AbdValue(const AbdValue& other) : AbdValue(other.data, other.size) {}
AbdValue& AbdValue::operator=(const AbdValue& other) {
    if (this != &other) {
        AbdValue copy(other);
        std::swap(data, copy.data);
        std::swap(size, copy.size);
    }
    return *this;
}
AbdValue::AbdValue(AbdValue&& other) noexcept : size(std::exchange(other.size, 0)), data(std::exchange(other.data, nullptr)) {}
AbdValue& AbdValue::operator=(AbdValue&& other) noexcept {
    if (this != &other) {
        delete[] data;
        data = std::exchange(other.data, nullptr);
        size = std::exchange(other.size, 0);
    }
    return *this;
}
AbdValue::~AbdValue() { delete[] data; }
std::shared_ptr<AbdValue> AbdValue::fromBytes(const unsigned char* bytes, std::size_t length, bool requireExact) {
    if (!bytes || length < 4) throw std::invalid_argument("truncated ABD length prefix");
    const int payloadLength = b2i(bytes);
    if (payloadLength < 0 || static_cast<std::size_t>(payloadLength) > length - 4)
        throw std::invalid_argument("negative or truncated ABD payload");
    if (requireExact && static_cast<std::size_t>(payloadLength) != length - 4)
        throw std::invalid_argument("trailing bytes after ABD frame");
    return std::make_shared<AbdValue>(bytes + 4, payloadLength);
}
std::shared_ptr<unsigned char[]> AbdValue::toBytes() {
    validPayload(*this);
    std::shared_ptr<unsigned char[]> result(new unsigned char[static_cast<std::size_t>(size) + 4]);
    i2b(size, result.get());
    if (size) std::memcpy(result.get() + 4, data, static_cast<std::size_t>(size));
    return result;
}
std::shared_ptr<AbdValue> AbdStack::toAbdValue() {
    std::size_t length = 0;
    for (const auto& v : vs) {
        if (!v) throw std::invalid_argument("null ABD stack member");
        validPayload(*v);
        const auto partLength = static_cast<std::size_t>(v->size) + 4;
        if (partLength > static_cast<std::size_t>(std::numeric_limits<int>::max()) - length)
            throw std::length_error("ABD stack exceeds 32-bit payload length");
        length += partLength;
    }
    std::vector<unsigned char> buffer(length);
    std::size_t offset = 0;
    for (const auto& v : vs) {
        i2b(v->size, buffer.data() + offset);
        if (v->size) std::memcpy(buffer.data() + offset + 4, v->data, static_cast<std::size_t>(v->size));
        offset += static_cast<std::size_t>(v->size) + 4;
    }
    return std::make_shared<AbdValue>(buffer.data(), static_cast<int>(length));
}
AbdStack::AbdStack(std::shared_ptr<AbdValue> v) {
    if (!v) throw std::invalid_argument("null ABD stack payload");
    validPayload(*v);
    std::size_t offset = 0;
    while (offset < static_cast<std::size_t>(v->size)) {
        auto member = AbdValue::fromBytes(v->data + offset, static_cast<std::size_t>(v->size) - offset, false);
        offset += static_cast<std::size_t>(member->size) + 4;
        vs.push_back(std::move(member));
    }
}
AbdMap::AbdMap(std::shared_ptr<AbdStack> v) {
    DepthGuard guard;
    if (!v || v->vs.size() % 3 != 0) throw std::invalid_argument("ABD map requires name/type/value triples");
    for (std::size_t i = 0; i < v->vs.size(); i += 3) {
        const auto name = StringAbdValue(v->vs[i]).data;
        if (get(name)) throw std::invalid_argument("duplicate ABD map key: " + name);
        put(name, decode(v->vs[i + 1], v->vs[i + 2]));
    }
}
std::shared_ptr<AbdValue> AbdMap::toAbdValue() {
    DepthGuard guard;
    AbdStack stack;
    for (const auto& [key, value] : values) {
        if (!value) throw std::invalid_argument("null ABD map value");
        stack.vs.push_back(StringAbdValue(key).toAbdValue());
        stack.vs.push_back(value->typeValue());
        stack.vs.push_back(value->toAbdValue());
    }
    return stack.toAbdValue();
}
std::shared_ptr<AbdValue> AbdMap::typeValue() { return std::make_shared<AbdValue>(i2b(2).get(), 4); }
std::shared_ptr<AbdMapValue> AbdMap::get(std::string key) {
    for (const auto& entry : values) if (entry.first == key) return entry.second;
    return nullptr;
}
void AbdMap::put(std::string key, std::shared_ptr<AbdMapValue> value) {
    if (!value) throw std::invalid_argument("null ABD map value");
    for (auto& entry : values) {
        if (entry.first == key) { entry.second = std::move(value); return; }
    }
    values.emplace_back(std::move(key), std::move(value));
}
AbdMapValue* AbdMap::deepCopy() {
    DepthGuard guard;
    auto copy = std::make_unique<AbdMap>();
    for (const auto& [key, value] : values) {
        if (!value) throw std::invalid_argument("null ABD map value");
        copy->put(key, std::shared_ptr<AbdMapValue>(value->deepCopy()));
    }
    return copy.release();
}
AbdArray::AbdArray(std::shared_ptr<AbdStack> v) {
    DepthGuard guard;
    if (!v || v->vs.size() % 2 != 0) throw std::invalid_argument("ABD array requires type/value pairs");
    for (std::size_t i = 0; i < v->vs.size(); i += 2) values.push_back(decode(v->vs[i], v->vs[i + 1]));
}
std::shared_ptr<AbdValue> AbdArray::toAbdValue() {
    DepthGuard guard;
    AbdStack stack;
    for (const auto& value : values) {
        if (!value) throw std::invalid_argument("null ABD array value");
        stack.vs.push_back(value->typeValue());
        stack.vs.push_back(value->toAbdValue());
    }
    return stack.toAbdValue();
}
std::shared_ptr<AbdValue> AbdArray::typeValue() { return std::make_shared<AbdValue>(i2b(0xad).get(), 4); }
std::shared_ptr<AbdMapValue> AbdArray::get(int index) {
    if (index < 0) throw std::out_of_range("negative ABD array index");
    return values.at(static_cast<std::size_t>(index));
}
void AbdArray::push_back(std::shared_ptr<AbdMapValue> v) {
    if (!v) throw std::invalid_argument("null ABD array value");
    values.push_back(std::move(v));
}
AbdMapValue* AbdArray::deepCopy() {
    DepthGuard guard;
    auto copy = std::make_unique<AbdArray>();
    for (const auto& value : values) {
        if (!value) throw std::invalid_argument("null ABD array value");
        copy->push_back(std::shared_ptr<AbdMapValue>(value->deepCopy()));
    }
    return copy.release();
}
}
