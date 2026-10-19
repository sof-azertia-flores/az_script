#ifndef AZSCRIPT_CORE_STATEMENT_H
#define AZSCRIPT_CORE_STATEMENT_H
#include "ownership.h"

namespace azertian {class environment;class variable;}
namespace azertian::statement {
// A waterline belongs to one generation of a temporary list. Reusing the
// frame slot must never let an old statement finalize a later frame's values.
struct Mark {ownership::TempListId list;std::size_t count=0;};
// The caller holds the list lease and supplies its current waterline.
inline Mark begin(ownership::TempListId list,std::size_t count) noexcept{return {list,count};}
bool pending(Mark mark) noexcept;
void finish(Mark mark,ownership::Site context);

// Transitional engine adapters: allocation, evaluation and store have already
// completed. Both engines commit the same declaration/ownership sequence.
// Validation stays before initializer evaluation; do not repeat it here since
// a hand-written initializer can declare the destination slot itself.
void bind_parameter(const std::shared_ptr<variable>& storage,std::size_t index,
                    const std::shared_ptr<environment>& scope);
void declare_local(const std::shared_ptr<variable>& storage,std::size_t index,
                   const std::shared_ptr<environment>& scope);
}
#endif
