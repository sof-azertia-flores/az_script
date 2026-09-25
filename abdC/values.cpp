#include "azertianBinaryDataValues.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>

namespace azertian {
namespace {
void requireValue(const std::shared_ptr<AbdValue>& value, int length = -1) {
    if (!value || value->size < 0 || (value->size != 0 && !value->data)
        || (length >= 0 && value->size != length))
        throw std::invalid_argument("invalid ABD scalar payload length");
}
std::shared_ptr<AbdValue> type(int code) { return std::make_shared<AbdValue>(i2b(code).get(), 4); }
template<class T> std::shared_ptr<AbdValue> encodeFloating(T value) {
    static_assert(std::numeric_limits<T>::is_iec559);
    auto bytes = std::bit_cast<std::array<unsigned char, sizeof(T)>>(value);
    if constexpr (std::endian::native == std::endian::big) std::reverse(bytes.begin(), bytes.end());
    return std::make_shared<AbdValue>(bytes.data(), static_cast<int>(bytes.size()));
}
template<class T> T decodeFloating(const std::shared_ptr<AbdValue>& value) {
    requireValue(value, sizeof(T));
    std::array<unsigned char, sizeof(T)> bytes{};
    std::memcpy(bytes.data(), value->data, bytes.size());
    if constexpr (std::endian::native == std::endian::big) std::reverse(bytes.begin(), bytes.end());
    return std::bit_cast<T>(bytes);
}
}
StringAbdValue::StringAbdValue(std::string value) : data(std::move(value)) {}
StringAbdValue::StringAbdValue(const char* value) {
    if (!value) throw std::invalid_argument("null ABD string");
    data = value;
}
StringAbdValue::StringAbdValue(const std::shared_ptr<AbdValue>& value) {
    requireValue(value);
    if (value->size) data.assign(reinterpret_cast<const char*>(value->data), static_cast<std::size_t>(value->size));
}
std::shared_ptr<AbdValue> StringAbdValue::toAbdValue() {
    if (data.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) throw std::length_error("ABD string too long");
    return std::make_shared<AbdValue>(reinterpret_cast<const unsigned char*>(data.data()), static_cast<int>(data.size()));
}
std::shared_ptr<AbdValue> StringAbdValue::typeValue() { return type(1); }
AbdMapValue* StringAbdValue::deepCopy() { return new StringAbdValue(data); }
IntAbdValue::IntAbdValue(int value) : data(value) {}
IntAbdValue::IntAbdValue(const std::shared_ptr<AbdValue>& value) { requireValue(value, 4); data = b2i(value->data); }
std::shared_ptr<AbdValue> IntAbdValue::toAbdValue() { return type(data); }
std::shared_ptr<AbdValue> IntAbdValue::typeValue() { return type(3); }
AbdMapValue* IntAbdValue::deepCopy() { return new IntAbdValue(data); }
DoubleAbdValue::DoubleAbdValue(double value) : data(value) {}
DoubleAbdValue::DoubleAbdValue(const std::shared_ptr<AbdValue>& value) : data(decodeFloating<double>(value)) {}
std::shared_ptr<AbdValue> DoubleAbdValue::toAbdValue() { return encodeFloating(data); }
std::shared_ptr<AbdValue> DoubleAbdValue::typeValue() { return type(0xce1066); }
AbdMapValue* DoubleAbdValue::deepCopy() { return new DoubleAbdValue(data); }
FloatAbdValue::FloatAbdValue(float value) : data(value) {}
FloatAbdValue::FloatAbdValue(const std::shared_ptr<AbdValue>& value) : data(decodeFloating<float>(value)) {}
std::shared_ptr<AbdValue> FloatAbdValue::toAbdValue() { return encodeFloating(data); }
std::shared_ptr<AbdValue> FloatAbdValue::typeValue() { return type(0xce867); }
AbdMapValue* FloatAbdValue::deepCopy() { return new FloatAbdValue(data); }
BoolAbdValue::BoolAbdValue(bool value) : data(value) {}
BoolAbdValue::BoolAbdValue(const std::shared_ptr<AbdValue>& value) {
    requireValue(value, 1);
    if (value->data[0] > 1) throw std::invalid_argument("ABD boolean must be 0 or 1");
    data = value->data[0] == 1;
}
std::shared_ptr<AbdValue> BoolAbdValue::toAbdValue() {
    const unsigned char b = data ? 1 : 0;
    return std::make_shared<AbdValue>(&b, 1);
}
std::shared_ptr<AbdValue> BoolAbdValue::typeValue() { return type(0x0d00); }
AbdMapValue* BoolAbdValue::deepCopy() { return new BoolAbdValue(data); }
ByteArrayValue::ByteArrayValue(const unsigned char* bytes, int size, int typeCode) : wireType(typeCode) {
    if (size < 0 || (size && !bytes)) throw std::invalid_argument("invalid ABD byte array");
    if (wireType != 0xce2009 && wireType != 0xce200a) throw std::invalid_argument("invalid ABD byte-array type");
    auto copy = std::make_unique<unsigned char[]>(static_cast<std::size_t>(size));
    if (size) std::memcpy(copy.get(), bytes, static_cast<std::size_t>(size));
    data = copy.release();
    length = size;
}
ByteArrayValue::ByteArrayValue(const std::shared_ptr<AbdValue>& value, int typeCode) : wireType(typeCode) {
    requireValue(value);
    ByteArrayValue copy(value->data, value->size, typeCode);
    *this = std::move(copy);
}
ByteArrayValue::ByteArrayValue(const ByteArrayValue& other) : ByteArrayValue(other.data, other.length, other.wireType) {}
ByteArrayValue& ByteArrayValue::operator=(const ByteArrayValue& other) {
    if (this != &other) { ByteArrayValue copy(other); *this = std::move(copy); }
    return *this;
}
ByteArrayValue::ByteArrayValue(ByteArrayValue&& other) noexcept
    : data(std::exchange(other.data, nullptr)), length(std::exchange(other.length, 0)), wireType(other.wireType) {}
ByteArrayValue& ByteArrayValue::operator=(ByteArrayValue&& other) noexcept {
    if (this != &other) {
        delete[] data;
        data = std::exchange(other.data, nullptr);
        length = std::exchange(other.length, 0);
        wireType = other.wireType;
    }
    return *this;
}
ByteArrayValue::~ByteArrayValue() { delete[] data; }
std::shared_ptr<AbdValue> ByteArrayValue::toAbdValue() { return std::make_shared<AbdValue>(data, length); }
std::shared_ptr<AbdValue> ByteArrayValue::typeValue() { return type(wireType); }
AbdMapValue* ByteArrayValue::deepCopy() { return new ByteArrayValue(data, length, wireType); }
}
