#include "ownership.h"
#include "../heepalloc.h"
#include <atomic>
#include <map>
#include <mutex>

namespace azertian {
std::recursive_mutex& runtime_mutex();
namespace ownership {
namespace {
// Registered domains do not keep a script/session alive: each arena removes
// its entries in its destructor. Pool ids are never reused, so a retired arena
// cannot be confused with a later one even when its address is reused.
auto& registry() {
    // Hosts can retain environments until static teardown. Keep the registry
    // available then; its entries are removed by each arena destructor.
    static auto* domains=new std::map<std::uint64_t,Arena*>;
    return *domains;
}
// The arena that issued the last id of each kind resolved: a frame's scopes and
// temporaries resolve again on every statement and release. Lookups that race
// each other only exchange whole pointers; the arena clears its own entry.
template<class Id> std::atomic<Arena*>& last_domain() noexcept {static std::atomic<Arena*> arena{nullptr};return arena;}
template<class Id> auto lookup(Id id) noexcept -> decltype(std::declval<Arena&>().find(id)) {
    if(!id)return nullptr;
    auto& last=last_domain<Id>();
    auto* arena=last.load(std::memory_order_acquire);
    if(!arena||!arena->issues(id)) {
        auto entry=registry().find(id.arena);
        if(entry==registry().end())return nullptr;
        arena=entry->second;last.store(arena,std::memory_order_release);
    }
    return arena->find(id);
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
    domains.emplace(arena->scopes_.arena_id(),arena.get());
    domains.emplace(arena->temporaries_.arena_id(),arena.get());
    return arena;
}
Arena::~Arena() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    registry().erase(scopes_.arena_id());registry().erase(temporaries_.arena_id());
    for(auto* last:{&last_domain<ScopeId>(),&last_domain<TempListId>()}) {
        auto* self=this;last->compare_exchange_strong(self,nullptr,std::memory_order_acq_rel);
    }
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
Scope::Scope(std::shared_ptr<Arena> arena,Links links):lease_(std::move(arena)) {
    auto& own=record();own.id=id();own.parent=links.parent;own.caller=links.caller;own.temporaries=links.temporaries;
}
void Scope::abandon() noexcept {
    if(abandons_&&lease_.valid()&&!record().owned.empty())heap::discard_owned(id(),record());
}
void Arena::share(ScopeId id) noexcept {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());++scopes_.try_resolve(id)->holders;
}
void Arena::release(ScopeId id) noexcept {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(auto* record=scopes_.try_resolve(id);record&&--record->holders==0)scopes_.retire(id);
}
ScopeId frame_root(ScopeId scope) noexcept {
    const auto* start=find(scope);
    if(!start)return {};
    ScopeId root=scope;
    for(auto id=start->parent;const auto* record=find(id);id=record->parent) {
        if(record->temporaries!=start->temporaries)break;
        root=id;
    }
    return root;
}
ScopeId transfer_parent(ScopeId scope) noexcept {
    const auto* record=find(scope);
    if(!record)return {};
    if(find(record->caller))return record->caller;
    return find(record->parent)?record->parent:ScopeId{};
}
}
}
