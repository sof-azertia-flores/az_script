#ifndef AZSCRIPT_CORE_SIGNATURE_H
#define AZSCRIPT_CORE_SIGNATURE_H
#include "types.h"
#include <vector>

namespace azertian {
// A callable's type: return and parameter tags, hidden generic contexts, and
// entry kind (1 for internal placement initializers).
struct function_signature {
    int return_type=VOID_VALUE;
    std::vector<int> param_types;
    int hidden_count=0,entry_kind=0;
    bool operator==(const function_signature&) const=default;
};
}
#endif
