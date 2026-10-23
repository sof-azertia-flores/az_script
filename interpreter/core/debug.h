#ifndef AZSCRIPT_CORE_DEBUG_H
#define AZSCRIPT_CORE_DEBUG_H
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace azertian {
// A source position of an exec debug record; file indexes function_debug::files.
// Line and column are 1-based; a header position of 0:0 is unknown.
struct source_position {
    std::uint32_t file=0,line=0,column=0,inline_index=0;
};
// The optional debug record of a function, read from an exec compiled with -g. It
// never affects execution: tree expression locations and VM source maps index
// its locations, and both engines report trace frames from this one record.
struct inline_debug {
    std::string name;
    source_position header;
    std::uint32_t call_site=0; // zero-based location in the caller or enclosing inline
};
struct function_debug {
    std::string name;
    std::shared_ptr<const std::vector<std::string>> files;
    source_position header;
    std::vector<source_position> locations;
    std::vector<inline_debug> inlined;
};
}
#endif
