#include <azscript/runtime.hpp>

#include <bit>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>

namespace {
using azertian::variable;
int checks=0;
void check(bool condition,const char* message) {
    ++checks;
    if(!condition) throw std::runtime_error(message);
}
template<class T> void rejects(const variable& value) {
    bool rejected=false;
    try { (void)value.get<T>(); }
    catch(const std::invalid_argument& error) {
        rejected=std::string(error.what())=="Variable value has the wrong type";
    }
    check(rejected,"Wrong typed read must fail before payload access");
}
static_assert(noexcept(std::declval<const variable&>().type_tag()));
static_assert(noexcept(std::declval<const variable&>().object_handle()));
static_assert(noexcept(std::declval<variable&>().swap_storage(std::declval<variable&>())));
static_assert(std::is_same_v<decltype(std::declval<variable&>().get<int>()),const int&>);
static_assert(std::is_same_v<decltype(std::declval<const variable&>().get<std::string>()),const std::string&>);
}

// This host uses only the public value API. Keep it usable when the runtime
// changes payload storage, so copies and rejected reads retain their contract.
int main() {
    using namespace azertian;
    using namespace std::string_literals;
    try {
        variable value;
        check(value.type_tag()==VOID_VALUE,"Default value is void");
        check(!value.object_handle(),"Void has no object handle");
        rejects<int>(value);
        value.setValue(std::numeric_limits<int>::min());
        check(value.type_tag()==INT_VALUE && value.get<int>()==std::numeric_limits<int>::min(),"Integer lower bound");
        check(!value.object_handle(),"A scalar has no object handle");
        rejects<float>(value);
        value.setValue(std::numeric_limits<int>::max());
        variable copy(value);
        value.setValue(0);
        check(copy.get<int>()==std::numeric_limits<int>::max(),"Host copies preserve argument snapshots");
        copy=copy;
        check(copy.get<int>()==std::numeric_limits<int>::max(),"Self-assignment preserves value");

        constexpr std::uint64_t all_bits=std::numeric_limits<std::uint64_t>::max();
        value.setValue(address{all_bits});
        check(value.type_tag()==ADDRESS_VALUE && value.get<address>().value==all_bits,"Address retains all 64 bits");
        rejects<int>(value);
        value.setValue(-0.0f);
        check(value.type_tag()==FLOAT_VALUE && std::bit_cast<std::uint32_t>(value.get<float>())==0x80000000u,"Float retains negative zero");
        value.setValue(-0.0);
        check(value.type_tag()==DOUBLE_VALUE && std::bit_cast<std::uint64_t>(value.get<double>())==0x8000000000000000ull,"Double retains negative zero");
        rejects<float>(value);
        value.setValue(true);
        check(value.type_tag()==BOOLEAN_VALUE && value.get<bool>(),"Boolean true");
        value.setValue(false);
        check(!value.get<bool>(),"Boolean false");

        const std::string text="\xe4\xbd\xa0\xe5\xa5\xbd\0value"s;
        value.setValue(text);
        check(value.type_tag()==STRING_VALUE && value.get<std::string>()==text,"String retains UTF-8 and embedded null");
        copy=value;
        value.setValue("changed");
        check(copy.get<std::string>()==text,"String copy survives source replacement");
        rejects<address>(value);
        value.setValue(std::shared_ptr<slot_block>{});
        check(value.type_tag()==OBJECT_VALUE && !value.get<std::shared_ptr<slot_block>>(),"Null object handle remains an object value");
        check(!value.object_handle(),"Null object handle is preserved by unchecked object query");
        rejects<std::string>(value);
        value.setValue(nullptr);
        check(value.type_tag()==VOID_VALUE,"Clearing replaces the previous type");
        rejects<std::shared_ptr<slot_block>>(value);
        value.name="destination";
        copy.name="source";
        value.swap_storage(copy);
        check(value.type_tag()==STRING_VALUE && value.get<std::string>()==text && copy.type_tag()==VOID_VALUE,"Storage swap transfers the payload and type together");
        check(value.name=="destination" && copy.name=="source","Storage swap retains each slot's metadata");
        std::cout<<checks<<" public typed-value checks passed\n";
        return 0;
    } catch(const std::exception& error) {
        std::cerr<<error.what()<<'\n';
        return 1;
    }
}
