#ifndef AZSCRIPT_VM_STORAGE_H
#define AZSCRIPT_VM_STORAGE_H

#include "../core/storage.h"
#include <cstddef>

namespace azertian::vm {
// Default VM execution workspace for one script session. Legal boundaries need
// up to 72.1 MiB (one fully dense 1,048,576-local frame) and 80.1 MiB peak
// (32,767-local frames recursing to the default call depth of 256); see
// docs/runtime-performance-roadmap/STORAGE_DESIGN.md section 9.
inline constexpr std::size_t default_workspace_bytes=std::size_t{128}*1024*1024;

struct SlotTag {};
struct FrameTag {};
struct ScopeTag {};
using SlotId=StorageId<SlotTag>;
using FrameId=StorageId<FrameTag>;
using ScopeId=StorageId<ScopeTag>;

using SlotPool=SegmentedPool<Value,SlotTag>;
template<class T> using FramePool=SegmentedPool<T,FrameTag>;
template<class T> using ScopePool=SegmentedPool<T,ScopeTag>;

}
#endif
