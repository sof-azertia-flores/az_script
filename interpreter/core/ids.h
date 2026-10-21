#ifndef AZSCRIPT_CORE_IDS_H
#define AZSCRIPT_CORE_IDS_H
#include <bit>
#include <cstdint>

// Function-id layout shared by the loader, the linker and both engines: the
// high 16 bits are the namespace, the low 16 bits the id within it.
namespace azertian::ids {
inline constexpr int builtin_namespace=0x0abd;
inline constexpr int main_function=0x0fff0000;
constexpr int namespace_of(int id) noexcept {return static_cast<int>(static_cast<std::uint32_t>(id)>>16);}
constexpr int local_of(int id) noexcept {return static_cast<int>(static_cast<std::uint32_t>(id)&0xffffu);}
constexpr int relocate(int id,int name_space) noexcept {
    return std::bit_cast<int>((static_cast<std::uint32_t>(name_space)<<16)|(static_cast<std::uint32_t>(id)&0xffffu));
}
// Builtins, in the order of the runtime's builtin table. The compiler emits
// object_alloc only from class factories; its source table stops at str_ends_with.
// retired (0x0abd0001, the removed getDepth) has no function and is never reassigned.
enum class builtin : int {
    print=0x0abd0000,retired,mem_free,alloc,make_free,mem_send_up,mem_get,load_extern_library,
    reflect_invoke_function,reflect_get_hint_namespace,reflect_hint_loaded,str_length,str_byte_length,
    str_substring,str_char_at,str_code_at,str_from_code,str_index_of,str_starts_with,str_ends_with,object_alloc,
};
constexpr int id(builtin value) noexcept {return static_cast<int>(value);}
inline constexpr int builtin_count=id(builtin::object_alloc)-id(builtin::print)+1;
constexpr bool is_builtin(int function_id) noexcept {return namespace_of(function_id)==builtin_namespace;}
// Raw allocation and ownership builtins act on the calling scope's records.
constexpr bool is_heap_builtin(int function_id) noexcept {
    if(!is_builtin(function_id))return false;
    const auto value=static_cast<builtin>(function_id);
    return (value>=builtin::mem_free&&value<=builtin::mem_get)||value==builtin::object_alloc;
}
static_assert(id(builtin::mem_get)==0x0abd0006&&id(builtin::load_extern_library)==0x0abd0007&&
              id(builtin::str_ends_with)==0x0abd0013&&id(builtin::object_alloc)==0x0abd0014);
}
#endif
