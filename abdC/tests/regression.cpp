#include "library.h"
#include "azertianBinaryDataValues.h"
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <type_traits>

using namespace azertian;
static_assert(!std::is_convertible_v<int, address>);
static_assert(!std::is_convertible_v<address, int>);
static_assert(!std::is_convertible_v<address, std::uint64_t>);
namespace {
void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template<class F> void rejected(F fn) {
    try { fn(); } catch (const std::exception&) { return; }
    throw std::runtime_error("malformed input was accepted");
}
template<class T> std::shared_ptr<T> typed(const std::shared_ptr<AbdMapValue>& value) {
    auto result = std::dynamic_pointer_cast<T>(value);
    check(static_cast<bool>(result), "wrong decoded type");
    return result;
}
std::shared_ptr<AbdMap> fixture() {
    auto result = std::make_shared<AbdMap>();
    result->put("intMin", std::make_shared<IntAbdValue>(std::numeric_limits<int>::min()));
    result->put("intMax", std::make_shared<IntAbdValue>(std::numeric_limits<int>::max()));
    result->put("double", std::make_shared<DoubleAbdValue>(-1234.125));
    result->put("float", std::make_shared<FloatAbdValue>(1.25f));
    result->put("bool", std::make_shared<BoolAbdValue>(true));
    result->put("text", std::make_shared<StringAbdValue>(std::string("你好\0ABD", 10)));
    result->put("empty", std::make_shared<StringAbdValue>(""));
    const unsigned char bytes[] = {0, 0, 0xff, 0x80};
    result->put("bytes", std::make_shared<ByteArrayValue>(bytes, 4, 0xce200a));
    const unsigned char big[] = {0xff, 0x7f};
    result->put("big", std::make_shared<ByteArrayValue>(big, 2));
    auto array = std::make_shared<AbdArray>();
    array->push_back(std::make_shared<IntAbdValue>(-7));
    auto child = std::make_shared<AbdMap>();
    child->put("nested", std::make_shared<BoolAbdValue>(false));
    array->push_back(child);
    result->put("array", array);
    result->put("negativeZero", std::make_shared<DoubleAbdValue>(-0.0));
    result->put("nan", std::make_shared<FloatAbdValue>(std::bit_cast<float>(std::uint32_t(0x7fc12345))));
    result->put("addressZero", std::make_shared<AddressAbdValue>(address{}));
    result->put("addressEndian", std::make_shared<AddressAbdValue>(address(0x0102030405060708ULL)));
    result->put("addressMax", std::make_shared<AddressAbdValue>(address(UINT64_MAX)));
    return result;
}
void validate(const std::shared_ptr<AbdMap>& value) {
    check(value->values.size() == 15, "map cardinality/fallthrough");
    check(typed<IntAbdValue>(value->get("intMin"))->data == std::numeric_limits<int>::min(), "minimum signed integer");
    check(typed<IntAbdValue>(value->get("intMax"))->data == std::numeric_limits<int>::max(), "maximum signed integer");
    check(typed<DoubleAbdValue>(value->get("double"))->data == -1234.125, "double endian");
    check(typed<FloatAbdValue>(value->get("float"))->data == 1.25f, "float endian");
    check(typed<BoolAbdValue>(value->get("bool"))->data, "boolean");
    check(typed<StringAbdValue>(value->get("text"))->data == std::string("你好\0ABD", 10), "UTF-8 or embedded NUL");
    check(typed<StringAbdValue>(value->get("empty"))->data.empty(), "empty string");
    auto bytes = typed<ByteArrayValue>(value->get("bytes"));
    const unsigned char expected[] = {0, 0, 0xff, 0x80};
    check(bytes->length == 4 && std::memcmp(bytes->data, expected, 4) == 0 && bytes->wireType == 0xce200a, "raw bytes preservation");
    auto big = typed<ByteArrayValue>(value->get("big"));
    check(big->length == 2 && big->data[0] == 0xff && big->data[1] == 0x7f && big->wireType == 0xce2009, "BigInteger wire bytes");
    auto array = typed<AbdArray>(value->get("array"));
    check(array->values.size() == 2 && typed<IntAbdValue>(array->get(0))->data == -7, "array cardinality");
    check(!typed<BoolAbdValue>(typed<AbdMap>(array->get(1))->get("nested"))->data, "nested map");
    check(std::signbit(typed<DoubleAbdValue>(value->get("negativeZero"))->data), "negative zero");
    check(std::bit_cast<std::uint32_t>(typed<FloatAbdValue>(value->get("nan"))->data) == 0x7fc12345, "NaN payload");
    check(typed<AddressAbdValue>(value->get("addressZero"))->data == address{}, "zero address");
    check(typed<AddressAbdValue>(value->get("addressEndian"))->data == address(0x0102030405060708ULL), "address endian");
    check(typed<AddressAbdValue>(value->get("addressMax"))->data == address(UINT64_MAX), "unsigned maximum address");
}
std::vector<unsigned char> frame(const std::shared_ptr<AbdMap>& map) {
    auto v = map->toAbdValue();
    auto b = v->toBytes();
    return {b.get(), b.get() + v->size + 4};
}
std::shared_ptr<AbdMap> read(const std::vector<unsigned char>& b) {
    return std::make_shared<AbdMap>(std::make_shared<AbdStack>(AbdValue::fromBytes(b.data(), b.size())));
}
void runTests() {
    auto source = fixture();
    auto bytes = frame(source);
    validate(read(bytes));
    check(frame(read(bytes)) == bytes, "byte-exact roundtrip");
    auto copy = std::shared_ptr<AbdMap>(static_cast<AbdMap*>(source->deepCopy()));
    copy->put("intMin", std::make_shared<IntAbdValue>(99));
    check(copy->values.size() == 15 && typed<IntAbdValue>(copy->get("intMin"))->data == 99, "map update");
    typed<AddressAbdValue>(copy->get("addressMax"))->data = address(42);
    typed<ByteArrayValue>(copy->get("bytes"))->data[0] = 99;
    typed<AbdMap>(typed<AbdArray>(copy->get("array"))->get(1))->put("nested", std::make_shared<BoolAbdValue>(true));
    validate(source);
    AbdValue a(bytes.data(), static_cast<int>(bytes.size()));
    AbdValue b = a;
    b.data[0] ^= 0xff;
    check(b.data[0] != a.data[0], "AbdValue copy owns separate bytes");
    b = a;
    AbdValue c = std::move(b);
    check(b.size == 0 && c.size == a.size, "AbdValue move");
    ByteArrayValue x(bytes.data(), 4), y = x;
    y.data[0] ^= 0xff;
    check(x.data[0] != y.data[0], "byte-array copy");
    for (std::size_t length = 0; length < bytes.size(); ++length)
        rejected([&] { AbdValue::fromBytes(bytes.data(), length); });
    auto trailing = bytes; trailing.push_back(0);
    rejected([&] { AbdValue::fromBytes(trailing.data(), trailing.size()); });
    auto negative = bytes; i2b(-1, negative.data());
    rejected([&] { read(negative); });
    auto huge = bytes; i2b(std::numeric_limits<int>::max(), huge.data());
    rejected([&] { read(huge); });
    unsigned char one[] = {1};
    auto shortValue = std::make_shared<AbdValue>(one, 1);
    rejected([&] { IntAbdValue v(shortValue); });
    rejected([&] { DoubleAbdValue v(shortValue); });
    rejected([&] { FloatAbdValue v(shortValue); });
    const unsigned char addressBytes[] = {8, 7, 6, 5, 4, 3, 2, 1};
    auto addressPayload = AddressAbdValue(address(0x0102030405060708ULL)).toAbdValue();
    check(addressPayload->size == 8 && std::memcmp(addressPayload->data, addressBytes, 8) == 0, "exact address wire bytes");
    for (int size = 0; size <= 9; ++size) {
        if (size == 8) continue;
        const unsigned char zeroBytes[9]{};
        auto payload = std::make_shared<AbdValue>(zeroBytes, size);
        rejected([&] { AddressAbdValue invalid(payload); });
        auto typedPayload = std::make_shared<AbdStack>();
        typedPayload->vs = {IntAbdValue(0xce200b).toAbdValue(), payload};
        rejected([&] { AbdArray invalid(typedPayload); });
    }
    unsigned char invalidBoolean[] = {2};
    rejected([&] { BoolAbdValue v(std::make_shared<AbdValue>(invalidBoolean, 1)); });
    auto malformed = std::make_shared<AbdStack>();
    malformed->vs.push_back(shortValue);
    rejected([&] { AbdMap v(malformed); });
    rejected([&] { AbdArray v(malformed); });
    malformed->vs.push_back(shortValue);
    rejected([&] { AbdArray v(malformed); });
    malformed->vs[0] = IntAbdValue(-1).toAbdValue();
    rejected([&] { AbdArray v(malformed); });
    auto array = std::make_shared<AbdArray>();
    rejected([&] { array->get(-1); }); rejected([&] { array->get(0); });
    array->push_back(array);
    rejected([&] { array->toAbdValue(); });
    rejected([&] { std::unique_ptr<AbdMapValue> copied(array->deepCopy()); });
    array->values.clear(); // release the deliberately constructed shared_ptr cycle
    // Exercise random external input and mutated valid frames under sanitizers.
    std::mt19937 rng(123456);
    for (int n = 0; n < 10000; ++n) {
        auto mutated = bytes;
        std::size_t index = static_cast<std::size_t>(rng()) % mutated.size();
        mutated[index] ^= static_cast<unsigned char>(1 + rng() % 255);
        try { auto value = read(mutated); value->toAbdValue(); } catch (const std::exception&) {}
    }
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[1]) == "--write") {
            auto bytes = frame(fixture());
            std::ofstream out(argv[2], std::ios::binary);
            out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
            check(static_cast<bool>(out), "cannot write fixture");
        } else if (argc >= 3 && std::string(argv[1]) == "--read") {
            std::ifstream in(argv[2], std::ios::binary);
            check(static_cast<bool>(in), "cannot read fixture");
            std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(in)), {});
            auto decoded = read(bytes);
            validate(decoded);
            check(frame(decoded) == bytes, "cross-language frame changed on re-encoding");
        } else runTests();
        std::cout << "ABD C++ regression passed\n";
        return 0;
    } catch (const std::exception& ex) { std::cerr << ex.what() << '\n'; return 1; }
}
