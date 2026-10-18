#include "statement.h"
#include "../blocks.h"

namespace azertian::statement {
bool pending(Mark mark) noexcept {
    auto* record=ownership::find(mark.list);
    return record&&record->values.size()>mark.count;
}
void finish(Mark mark,const std::shared_ptr<environment>& context) {
    blocks::finalize_temporaries(mark.list,mark.count,context);
}
void bind_parameter(const std::shared_ptr<variable>& storage,std::size_t index,
                    const std::shared_ptr<environment>& scope) {
    scope->frame->slots[index]=storage;blocks::bind_local(storage,scope);
}
void declare_local(const std::shared_ptr<variable>& storage,std::size_t index,
                   const std::shared_ptr<environment>& scope) {
    scope->declared_slots.push_back(index);bind_parameter(storage,index,scope);
}
}
