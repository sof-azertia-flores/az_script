#ifndef AZSCRIPT_CORE_OBJECT_ACCESS_H
#define AZSCRIPT_CORE_OBJECT_ACCESS_H
#include "../blocks.h"
#include "../heepalloc.h"
#include <optional>
#include <vector>

// Object-model internals the lifecycle state machines share with blocks.cpp
// and heepalloc.cpp. Not installed.
namespace azertian::blocks::detail {
// The live or destroying block an address names, or null.
std::shared_ptr<slot_block> lookup(address pointer);
}
namespace azertian::heap::detail {
bool registered(address pointer);
// destroy_registered's start for a registered object: it must not already
// be destroying; its destructor's fields are copied, then it is marked
// destroying.
void begin_destroy(address pointer,std::optional<int>& destructor,std::weak_ptr<azertian::script>& owner,
                   type_contexts& contexts);
// The slot variables of the allocation starting at pointer, in order.
std::vector<std::shared_ptr<variable>> allocation_slots(address pointer);
// destroy_registered's end: the registration goes, then the allocation.
void end_destroy(address pointer);
}
#endif
