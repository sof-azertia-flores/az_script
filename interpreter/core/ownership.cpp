#include "ownership.h"
#include <map>
#include <mutex>

namespace azertian {
std::recursive_mutex& runtime_mutex();
namespace ownership {
namespace {
// Weak domains do not keep a script/session alive, and retired arenas cannot
// be confused with a later arena even when its allocation address is reused.
auto& registry() {
    // Hosts can retain environments until static teardown. Keep the registry
    // available then; its weak entries are removed by each arena destructor.
    static auto* domains=new std::map<std::uint64_t,std::weak_ptr<Arena>>;
    return *domains;
}
template<class Id> auto lookup(Id id) noexcept -> decltype(std::declval<Arena&>().find(id)) {
    if(!id)return nullptr;
    auto entry=registry().find(id.arena);
    if(entry==registry().end())return nullptr;
    auto arena=entry->second.lock();
    return arena?arena->find(id):nullptr;
}
}
Arena::Arena(std::shared_ptr<WorkspaceBudget> budget)
    :budget_(budget?std::move(budget):std::make_shared<WorkspaceBudget>(std::numeric_limits<std::size_t>::max())),
     scopes_(*budget_),temporaries_(*budget_) {}
std::shared_ptr<Arena> Arena::create(std::shared_ptr<WorkspaceBudget> budget) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    // Initialize the registry before creating an arena whose destructor uses
    // it, so a first-use allocation failure cannot recur during unwinding.
    auto& domains=registry();
    auto arena=std::shared_ptr<Arena>(new Arena(std::move(budget)));
    domains.emplace(arena->scopes_.arena_id(),arena);
    domains.emplace(arena->temporaries_.arena_id(),arena);
    return arena;
}
Arena::~Arena() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    registry().erase(scopes_.arena_id());registry().erase(temporaries_.arena_id());
}
ScopeId Arena::create_scope() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());return scopes_.emplace();
}
TempListId Arena::create_temporaries() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());return temporaries_.emplace();
}
void Arena::retire(ScopeId id) noexcept {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());scopes_.retire(id);
}
void Arena::retire(TempListId id) noexcept {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());temporaries_.retire(id);
}
ScopeRecord* find(ScopeId id) noexcept {return lookup(id);}
TemporaryRecord* find(TempListId id) noexcept {return lookup(id);}
}
}
