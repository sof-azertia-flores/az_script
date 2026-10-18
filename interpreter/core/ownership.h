#ifndef AZSCRIPT_CORE_OWNERSHIP_H
#define AZSCRIPT_CORE_OWNERSHIP_H

#include "storage.h"
#include <memory>
#include <vector>

namespace azertian {
class script;
struct slot_block;
namespace ownership {
struct ScopeTag {};
struct TemporaryTag {};
using ScopeId=StorageId<ScopeTag>;
using TempListId=StorageId<TemporaryTag>;

// Records own their storage; neither the registry nor an object contains an
// engine environment/frame pointer. Engine adapters hold leases on records.
struct ScopeRecord {
    std::vector<address> owned;
    std::weak_ptr<azertian::script> script;
    // Links written when the scope is created: its frame's temporaries, its
    // lexical parent and the scope it was called from. A retired link ends a walk.
    TempListId temporaries;
    ScopeId parent,caller;
    bool closing=false;
};
struct TemporaryRecord {std::vector<std::shared_ptr<slot_block>> values;};

class Arena {
    std::shared_ptr<WorkspaceBudget> budget_;
    SegmentedPool<ScopeRecord,ScopeTag,64> scopes_;
    SegmentedPool<TemporaryRecord,TemporaryTag,64> temporaries_;
    explicit Arena(std::shared_ptr<WorkspaceBudget> budget);
public:
    static std::shared_ptr<Arena> create(std::shared_ptr<WorkspaceBudget> budget={});
    ~Arena();
    Arena(const Arena&)=delete;
    Arena& operator=(const Arena&)=delete;
    ScopeId create_scope();
    TempListId create_temporaries();
    void retire(ScopeId id) noexcept;
    void retire(TempListId id) noexcept;
    ScopeRecord* find(ScopeId id) noexcept{return scopes_.try_resolve(id);}
    TemporaryRecord* find(TempListId id) noexcept{return temporaries_.try_resolve(id);}
    bool issues(ScopeId id) const noexcept{return scopes_.arena_id()==id.arena;}
    bool issues(TempListId id) const noexcept{return temporaries_.arena_id()==id.arena;}
};

// The runtime lock serializes these lookups with retirement. Re-resolve an ID
// after any call into script/host code; a cached record pointer is not a lease.
ScopeRecord* find(ScopeId id) noexcept;
TemporaryRecord* find(TempListId id) noexcept;
// The outermost scope on the parent chain that still shares scope's frame.
ScopeId frame_root(ScopeId scope) noexcept;
// Where explicit allocation transfer goes: the caller, else the lexical parent.
ScopeId transfer_parent(ScopeId scope) noexcept;
// The number of scopes on the parent chain, scope included.
int depth(ScopeId scope) noexcept;

template<class Id> class Lease {
    std::shared_ptr<Arena> arena_;
    Id id_;
public:
    explicit Lease(std::shared_ptr<Arena> arena={})
        :arena_(arena?std::move(arena):Arena::create()) {
        if constexpr(std::is_same_v<Id,ScopeId>)id_=arena_->create_scope();
        else id_=arena_->create_temporaries();
    }
    ~Lease(){arena_->retire(id_);}
    Lease(const Lease&)=delete;
    Lease& operator=(const Lease&)=delete;
    Id id() const noexcept{return id_;}
    auto& record() const noexcept{return *arena_->find(id_);}
    const std::shared_ptr<Arena>& arena() const noexcept{return arena_;}
};
}
}
#endif
