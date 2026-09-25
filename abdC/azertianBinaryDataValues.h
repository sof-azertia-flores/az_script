//
// Created by Chæscetia Zeu on 25-3-23.
//

#ifndef AZERTIANBINARYDATAVALUES_H
#define AZERTIANBINARYDATAVALUES_H
#include "library.h"
#include "address.h"

namespace azertian {
    class StringAbdValue : public AbdMapValue {
        public:
        std::string data;
        StringAbdValue(std::string data);
        StringAbdValue()=default;

        StringAbdValue(const char * str);
        StringAbdValue(const std::shared_ptr<AbdValue> &value);

        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue * deepCopy() override;
    };
    class IntAbdValue : public AbdMapValue {
        public:
        int data = 0;
        IntAbdValue()=default;
        IntAbdValue(int data);
        IntAbdValue(const std::shared_ptr<AbdValue> &value);
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue * deepCopy() override;
    };

    class AddressAbdValue : public AbdMapValue {
    public:
        address data;
        AddressAbdValue() = default;
        explicit AddressAbdValue(address data);
        explicit AddressAbdValue(const std::shared_ptr<AbdValue>& value);
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue* deepCopy() override;
    };


    class DoubleAbdValue : public AbdMapValue {
    public:
        double data = 0;
        DoubleAbdValue()=default;
        DoubleAbdValue(double data);
        DoubleAbdValue(const std::shared_ptr<AbdValue> &value);
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue * deepCopy() override;
    };

    class FloatAbdValue : public AbdMapValue {
    public:
        float data = 0;
        FloatAbdValue()=default;
        FloatAbdValue(float data);
        FloatAbdValue(const std::shared_ptr<AbdValue> &value);
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue * deepCopy() override;
    };
    class BoolAbdValue : public AbdMapValue {
        public:
        bool data = false;
        BoolAbdValue()=default;
        BoolAbdValue(bool data);
        BoolAbdValue(const std::shared_ptr<AbdValue> &value);
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        AbdMapValue * deepCopy() override;
    };
    class ByteArrayValue : public AbdMapValue {
        public:
        unsigned char* data = nullptr;
        int length = 0;
        // 0xce2009 is the legacy BigInteger/raw tag; 0xce200a is unambiguous raw bytes.
        int wireType = 0xce2009;
        ByteArrayValue(const unsigned char* data, int length, int type = 0xce2009);
        ByteArrayValue(const std::shared_ptr<AbdValue> &value, int type = 0xce2009);
        ByteArrayValue(const ByteArrayValue& other);
        ByteArrayValue& operator=(const ByteArrayValue& other);
        ByteArrayValue(ByteArrayValue&& other) noexcept;
        ByteArrayValue& operator=(ByteArrayValue&& other) noexcept;
        std::shared_ptr<AbdValue> toAbdValue() override;
        std::shared_ptr<AbdValue> typeValue() override;
        ~ByteArrayValue();
        AbdMapValue * deepCopy() override;
    };

}
#endif //AZERTIANBINARYDATAVALUES_H
