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
    ScopeId id;
    std::vector<address> owned;
    std::weak_ptr<azertian::script> script;
    // Links written when the scope is created: its frame's temporaries, its
    // lexical parent and the scope it was called from. A retired link ends a walk.
    TempListId temporaries;
    ScopeId parent,caller;
    bool closing=false;
    // Leases on this record; the last one to end retires it.
    std::uint32_t holders=1;
};
struct TemporaryRecord {std::vector<std::shared_ptr<slot_block>> values;};
// The scope an object-model operation runs in, with its frame's temporaries
// when it has a frame. Whoever runs the operation keeps the scope alive, so
// the record pointers stay valid for the operation.
struct Site {
    ScopeRecord* record=nullptr;
    TemporaryRecord* temporaries=nullptr;
    ScopeId scope() const noexcept{return record?record->id:ScopeId{};}
};

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
    // A shared scope record is retired by its last lease.
    void share(ScopeId id) noexcept;
    void release(ScopeId id) noexcept;
    void release(TempListId id) noexcept{retire(id);}
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
    // Pool records keep their address until the id is retired.
    std::remove_reference_t<decltype(*std::declval<Arena&>().find(Id{}))>* record_;
public:
    explicit Lease(std::shared_ptr<Arena> arena={})
        :arena_(arena?std::move(arena):Arena::create()) {
        if constexpr(std::is_same_v<Id,ScopeId>)id_=arena_->create_scope();
        else id_=arena_->create_temporaries();
        record_=arena_->find(id_);
    }
    struct share_t {};
    // A second lease on the same scope record.
    Lease(const Lease& other,share_t) requires std::is_same_v<Id,ScopeId>
        :arena_(other.arena_),id_(other.id_),record_(other.record_) {arena_->share(id_);}
    Lease(Lease&& other) noexcept:arena_(std::move(other.arena_)),id_(other.id_),record_(other.record_) {other.record_=nullptr;}
    ~Lease(){if(record_)arena_->release(id_);}
    Lease(const Lease&)=delete;
    Lease& operator=(const Lease&)=delete;
    Lease& operator=(Lease&&)=delete;
    Id id() const noexcept{return id_;}
    auto& record() const noexcept{return *record_;}
    const std::shared_ptr<Arena>& arena() const noexcept{return arena_;}
    bool valid() const noexcept{return record_!=nullptr;}
};

// The ownership side of a lexical scope: its record, linked when it is
// created. An orderly end runs lifecycle::ScopeFinalizer first; whatever the
// scope still owns when it is abandoned or destroyed ends without scripts.
class Scope {
    Lease<ScopeId> lease_;
    bool abandons_=true;
public:
    struct Links {ScopeId parent,caller;TempListId temporaries;};
    explicit Scope(std::shared_ptr<Arena> arena={},Links links={});
    // A view of another scope's record for the host edge: it keeps the
    // record alive but never abandons what the scope owns.
    struct view_t {};
    Scope(const Scope& origin,view_t):lease_(origin.lease_,Lease<ScopeId>::share_t{}),abandons_(false) {}
    Scope(Scope&& other) noexcept=default;
    ~Scope(){abandon();}
    Scope(const Scope&)=delete;
    Scope& operator=(const Scope&)=delete;
    Scope& operator=(Scope&&)=delete;
    ScopeId id() const noexcept{return lease_.id();}
    ScopeRecord& record() const noexcept{return lease_.record();}
    const std::shared_ptr<Arena>& arena() const noexcept{return lease_.arena();}
    void abandon() noexcept;
};
}
}
#endif
