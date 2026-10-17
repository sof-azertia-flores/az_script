#ifndef AZSCRIPT_VM_VALUE_H
#define AZSCRIPT_VM_VALUE_H

#include "../../abdC/address.h"
#include <memory>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <variant>

namespace azertian {
inline constexpr int INT_VALUE=0, STRING_VALUE=1, FLOAT_VALUE=2, DOUBLE_VALUE=3,
                     BOOLEAN_VALUE=4, VOID_VALUE=5, ANY_VALUE=6, ADDRESS_VALUE=7, OBJECT_VALUE=8;
struct slot_block;

namespace vm {
// Values have no storage identity. Copying an object handle does not copy its
// fields or transfer its script ownership; the owning Slot/variable handles it.
// Strings are immutable and share their bytes until a value is replaced.
class Value {
    using String=std::shared_ptr<const std::string>;
    using Object=std::shared_ptr<slot_block>;
    using Storage=std::variant<std::monostate,int,String,float,double,bool,address,Object>;
    Storage storage_;
    static_assert(std::is_nothrow_move_constructible_v<Storage>);
    static_assert(std::is_nothrow_swappable_v<Storage>);
public:
    Value() noexcept=default;
    explicit Value(std::nullptr_t) noexcept {}
    template<class T> requires (std::is_same_v<T,int>||std::is_same_v<T,float>||
        std::is_same_v<T,double>||std::is_same_v<T,bool>||std::is_same_v<T,address>)
    explicit Value(T value) noexcept:storage_(std::in_place_type<T>,value) {}
    explicit Value(std::string value)
        :storage_(std::in_place_type<String>,std::make_shared<const std::string>(std::move(value))) {}
    explicit Value(const char* value) {
        if(value)storage_.emplace<String>(std::make_shared<const std::string>(value));
    }
    explicit Value(Object value) noexcept:storage_(std::in_place_type<Object>,std::move(value)) {}
    // Every alternative has a non-throwing copy. No operation can publish a
    // valueless variant: potentially throwing string creation happens first.
    Value(const Value& other) noexcept:storage_(other.storage_) {}
    Value(Value&& other) noexcept:storage_(std::move(other.storage_)) {
        other.storage_.emplace<std::monostate>();
    }
    Value& operator=(const Value& other) noexcept {
        if(this!=&other){Value copy(other);swap(copy);}return *this;
    }
    Value& operator=(Value&& other) noexcept {
        if(this!=&other){Value moved(std::move(other));swap(moved);}return *this;
    }
    int type_tag() const noexcept {
        constexpr int tags[]{VOID_VALUE,INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,
                             BOOLEAN_VALUE,ADDRESS_VALUE,OBJECT_VALUE};
        return tags[storage_.index()];
    }
    template<class T> const T& get() const {
        static_assert(std::is_same_v<T,int>||std::is_same_v<T,address>||
            std::is_same_v<T,std::string>||std::is_same_v<T,float>||std::is_same_v<T,double>||
            std::is_same_v<T,bool>||std::is_same_v<T,Object>,"Unsupported variable value type");
        if constexpr(std::is_same_v<T,std::string>) {
            if(const auto* value=std::get_if<String>(&storage_))return **value;
        } else if(const auto* value=std::get_if<T>(&storage_))return *value;
        throw std::invalid_argument("Variable value has the wrong type");
    }
    Object object_handle() const noexcept {
        const auto* value=std::get_if<Object>(&storage_);return value?*value:nullptr;
    }
    void swap(Value& other) noexcept {storage_.swap(other.storage_);}
};
}
}
#endif
