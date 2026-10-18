#ifndef AZSCRIPT_VM_SESSION_H
#define AZSCRIPT_VM_SESSION_H

#include "execution.h"
#include "loader.h"
#include <memory>
#include <span>

namespace azertian {class environment; class script; class variable;}

namespace azertian::vm {
// Internal migration entry point. Selection precedes the first flush, and an
// unsupported program fails preparation; execution never falls back to trees.
// The explicit workspace limit is provisional until R09's capacity acceptance.
void select_backend(script& owner,std::size_t workspace_limit);
struct WorkspaceUsage {std::size_t used_bytes=0,peak_bytes=0,limit_bytes=0;};
WorkspaceUsage workspace_usage(const script& owner);


class Session {
    struct Prepared;
    script& owner_;
    const std::size_t workspace_limit_;
    std::unique_ptr<Prepared> prepared_;
public:
    Session(script& owner,std::size_t workspace_limit);
    ~Session();
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    // The embedding script calls this after complete link validation and before
    // publishing relocations or running any lifecycle hook. A failed rebuild
    // preserves the previous prepared program and host globals.
    void prepare();
    const CompiledScript& compiled() const;
    // caller is the scope invoking the function; object frames return object
    // results and transfer ownership into it, as the tree executor does.
    std::shared_ptr<variable> invoke(std::uint32_t function,
        std::span<const std::shared_ptr<variable>> arguments,ExecutionPosition* position,
        ownership::Site caller);
    std::size_t workspace_bytes() const noexcept;
    std::size_t workspace_peak_bytes() const noexcept;
    std::size_t workspace_limit() const noexcept{return workspace_limit_;}
    void reset() noexcept;
};
}
#endif
