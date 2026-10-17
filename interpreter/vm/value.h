#ifndef AZSCRIPT_VM_VALUE_H
#define AZSCRIPT_VM_VALUE_H

#include "../../abdC/address.h"
#include <memory>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <new>
#include <utility>

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
    // A hand-written tagged union rather than std::variant: unoptimized and
    // sanitizer builds execute every interpreter copy/move through it, and
    // variant visitation is not inlined there. All alternatives are nothrow
    // to copy and move, so no operation can leave a partially built value.
    enum class Kind : unsigned char { none,integer,string,single,real,boolean,pointer,object };
    union {
        int integer_;
        float single_;
        double real_;
        bool boolean_;
        address pointer_;
        String string_;
        Object object_;
    };
    Kind kind_=Kind::none;
    static_assert(std::is_nothrow_copy_constructible_v<String>&&std::is_nothrow_move_constructible_v<String>);
    static_assert(std::is_nothrow_copy_constructible_v<Object>&&std::is_nothrow_move_constructible_v<Object>);
    static_assert(std::is_trivially_copyable_v<address>);
    void destroy() noexcept {
        if(kind_==Kind::string)string_.~String();
        else if(kind_==Kind::object)object_.~Object();
        kind_=Kind::none;
    }
    // Both helpers require *this to hold no value. move_from leaves source empty.
    void copy_from(const Value& source) noexcept {
        switch(source.kind_) {
        case Kind::none:break;
        case Kind::integer:integer_=source.integer_;break;
        case Kind::string: ::new(static_cast<void*>(&string_)) String(source.string_);break;
        case Kind::single:single_=source.single_;break;
        case Kind::real:real_=source.real_;break;
        case Kind::boolean:boolean_=source.boolean_;break;
        case Kind::pointer:pointer_=source.pointer_;break;
        case Kind::object: ::new(static_cast<void*>(&object_)) Object(source.object_);break;
        }
        kind_=source.kind_;
    }
    void move_from(Value& source) noexcept {
        if(source.kind_==Kind::string)::new(static_cast<void*>(&string_)) String(std::move(source.string_));
        else if(source.kind_==Kind::object)::new(static_cast<void*>(&object_)) Object(std::move(source.object_));
        else copy_from(source);
        kind_=source.kind_;source.destroy();
    }
public:
    Value() noexcept {}
    explicit Value(std::nullptr_t) noexcept {}
    explicit Value(int value) noexcept:integer_(value),kind_(Kind::integer) {}
    explicit Value(float value) noexcept:single_(value),kind_(Kind::single) {}
    explicit Value(double value) noexcept:real_(value),kind_(Kind::real) {}
    explicit Value(bool value) noexcept:boolean_(value),kind_(Kind::boolean) {}
    explicit Value(address value) noexcept:pointer_(value),kind_(Kind::pointer) {}
    // String creation can throw before anything is published.
    explicit Value(std::string value)
        :string_(std::make_shared<const std::string>(std::move(value))),kind_(Kind::string) {}
    explicit Value(const char* value) {
        if(value){::new(static_cast<void*>(&string_)) String(std::make_shared<const std::string>(value));kind_=Kind::string;}
    }
    explicit Value(Object value) noexcept:object_(std::move(value)),kind_(Kind::object) {}
    Value(const Value& other) noexcept {copy_from(other);}
    Value(Value&& other) noexcept {move_from(other);}
    // Copy/move before releasing the old alternative: the source may be owned,
    // directly or indirectly, by the object this value currently holds.
    Value& operator=(const Value& other) noexcept {
        if(this!=&other){Value copy(other);destroy();move_from(copy);}return *this;
    }
    Value& operator=(Value&& other) noexcept {
        if(this!=&other){Value moved(std::move(other));destroy();move_from(moved);}return *this;
    }
    ~Value(){destroy();}
    int type_tag() const noexcept {
        constexpr int tags[]{VOID_VALUE,INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,
                             BOOLEAN_VALUE,ADDRESS_VALUE,OBJECT_VALUE};
        return tags[static_cast<unsigned char>(kind_)];
    }
    template<class T> const T& get() const {
        static_assert(std::is_same_v<T,int>||std::is_same_v<T,address>||
            std::is_same_v<T,std::string>||std::is_same_v<T,float>||std::is_same_v<T,double>||
            std::is_same_v<T,bool>||std::is_same_v<T,Object>,"Unsupported variable value type");
        if constexpr(std::is_same_v<T,int>){if(kind_==Kind::integer)return integer_;}
        else if constexpr(std::is_same_v<T,std::string>){if(kind_==Kind::string)return *string_;}
        else if constexpr(std::is_same_v<T,float>){if(kind_==Kind::single)return single_;}
        else if constexpr(std::is_same_v<T,double>){if(kind_==Kind::real)return real_;}
        else if constexpr(std::is_same_v<T,bool>){if(kind_==Kind::boolean)return boolean_;}
        else if constexpr(std::is_same_v<T,address>){if(kind_==Kind::pointer)return pointer_;}
        else if(kind_==Kind::object)return object_;
        throw std::invalid_argument("Variable value has the wrong type");
    }
    Object object_handle() const noexcept {return kind_==Kind::object?object_:nullptr;}
    void swap(Value& other) noexcept {
        if(this==&other)return;
        Value held(std::move(other));other.move_from(*this);move_from(held);
    }
};
}
}
#endif
