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
    std::uint32_t file=0,line=0,column=0;
};
// The optional debug record of a function, read from an exec compiled with -g. It
// never affects execution: tree expression locations and VM source maps index
// its locations, and both engines report trace frames from this one record.
// A function inlined at a call site (exec v11 -g). Its body's locations name
// it; call_site is the 1-based location of the call, which may itself lie in
// an earlier inlined function.
struct inlined_function {
    int function_id=0;
    std::string name;
    std::uint32_t call_site=0;
};
struct function_debug {
    std::string name;
    std::shared_ptr<const std::vector<std::string>> files;
    source_position header;
    std::vector<source_position> locations;
    // exec v11 extended records only: the 1-based inlined function of each
    // location (0 when none, or empty without inlining), and the id trace
    // frames show for this function (a clone shows its original).
    std::vector<std::uint32_t> location_inlines;
    std::vector<inlined_function> inlined;
    int display_id=0;
    bool extended=false;
};
}
#endif
