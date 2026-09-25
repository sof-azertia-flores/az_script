#ifndef AZERTIANBINARYDATA_LIBRARY_H
#define AZERTIANBINARYDATA_LIBRARY_H

#include <cstddef>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace azertian {
class AbdValue;

// ABD consists of a signed, nonnegative 32-bit little-endian payload length
// followed by that many bytes. A stack payload is a sequence of ABD frames.
class AbdStack {
public:
    std::vector<std::shared_ptr<AbdValue>> vs;
    std::shared_ptr<AbdValue> toAbdValue();
    AbdStack() = default;
    explicit AbdStack(std::shared_ptr<AbdValue> v);
};

class AbdValue {
public:
    int size = 0;
    unsigned char* data = nullptr;
    // Copies a raw payload (the argument does NOT contain a length prefix).
    AbdValue(const unsigned char* dat, int size);
    // Legacy trusted-buffer API: the caller must guarantee a complete frame.
    [[deprecated("use fromBytes(bytes, length) for bounded decoding")]]
    explicit AbdValue(unsigned char* abd);
    AbdValue(const AbdValue& other);
    AbdValue& operator=(const AbdValue& other);
    AbdValue(AbdValue&& other) noexcept;
    AbdValue& operator=(AbdValue&& other) noexcept;
    ~AbdValue();
    std::shared_ptr<unsigned char[]> toBytes();
    static std::shared_ptr<AbdValue> fromBytes(const unsigned char* bytes,
                                             std::size_t length,
                                             bool requireExact = true);
};

// These primitive helpers require a pointer to at least four bytes.
void i2b(int i, unsigned char* n);
std::shared_ptr<unsigned char> i2b(int i);
int b2i(const unsigned char* b);

class AbdMapValue {
public:
    virtual ~AbdMapValue() = default;
    virtual std::shared_ptr<AbdValue> toAbdValue() = 0;
    virtual std::shared_ptr<AbdValue> typeValue() = 0;
    // The returned value is owned by the caller.
    virtual AbdMapValue* deepCopy() = 0;
};

class AbdMap : public AbdMapValue {
public:
    std::vector<std::pair<std::string, std::shared_ptr<AbdMapValue>>> values;
    AbdMap() = default;
    explicit AbdMap(std::shared_ptr<AbdStack> v);
    std::shared_ptr<AbdValue> toAbdValue() override;
    std::shared_ptr<AbdValue> typeValue() override;
    std::shared_ptr<AbdMapValue> get(std::string key);
    void put(std::string key, std::shared_ptr<AbdMapValue> value);
    AbdMapValue* deepCopy() override;
};

class AbdArray : public AbdMapValue {
public:
    std::vector<std::shared_ptr<AbdMapValue>> values;
    AbdArray() = default;
    explicit AbdArray(std::shared_ptr<AbdStack> v);
    std::shared_ptr<AbdValue> toAbdValue() override;
    std::shared_ptr<AbdValue> typeValue() override;
    std::shared_ptr<AbdMapValue> get(int index);
    void push_back(std::shared_ptr<AbdMapValue> v);
    AbdMapValue* deepCopy() override;
};
}
#endif
