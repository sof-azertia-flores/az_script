// Experimental VM observation host. Protocol 1 matches the independently built
// frozen runtime_differential_host.cpp; no observation fields are normalized.
// --tree explicitly selects the old backend for adapter self-checks only.
#include <azscript/runtime.hpp>
#ifndef AZSCRIPT_VM_REFERENCE_ONLY
#include "../interpreter/vm/session.h"
#endif
#include <blocks.h>
#include <heepalloc.h>

#include <bit>
#include <cstdint>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace azertian;
using V = std::shared_ptr<variable>;
constexpr int host_namespace = 0x6dff;

// Compile-time adaptation is confined to this test host. The archived runtime
// keeps its original headers and storage; the candidate can make them private.
template<class Variable> int tag(const Variable& value) {
    if constexpr (requires { value.type_tag(); }) return value.type_tag();
    else return value.type;
}
template<class T, class Variable> const T& scalar(const Variable& value) {
    if constexpr (requires { value.template get<T>(); }) return value.template get<T>();
    else return *static_cast<const T*>(value.value);
}

std::string quote(const std::string& value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\' << static_cast<char>(c);
        else if (c < 0x20)
            out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
        else out << static_cast<char>(c);
    }
    return out.str() + '"';
}
std::string hex(std::uint64_t value, unsigned width) {
    std::ostringstream out; out << std::hex << std::setfill('0') << std::setw(width) << value; return out.str();
}
std::string bytes_hex(const std::string& value) {
    std::string result; result.reserve(2 * value.size());
    for (unsigned char c : value) result += hex(c, 2);
    return result;
}
std::string array(const std::vector<std::string>& values) {
    std::string result = "[";
    for (const auto& value : values) { if (result.size() > 1) result += ','; result += value; }
    return result + ']';
}

// Object identities are assigned by structural visitation, never raw native
// pointers. Script-visible address values retain all their original bits.
struct observation {
    std::map<const slot_block*, std::size_t> objects;
    std::string value(const V& item, unsigned depth = 0) {
        if (!item) return "{\"tag\":\"null-host-pointer\"}";
        std::string payload;
        switch (tag(*item)) {
        case INT_VALUE: payload = std::to_string(scalar<int>(*item)); break;
        case STRING_VALUE: payload = quote(bytes_hex(scalar<std::string>(*item))); break;
        case FLOAT_VALUE: payload = quote(hex(std::bit_cast<std::uint32_t>(scalar<float>(*item)), 8)); break;
        case DOUBLE_VALUE: payload = quote(hex(std::bit_cast<std::uint64_t>(scalar<double>(*item)), 16)); break;
        case BOOLEAN_VALUE: payload = scalar<bool>(*item) ? "true" : "false"; break;
        case VOID_VALUE: payload = "null"; break;
        case ADDRESS_VALUE: payload = quote(hex(scalar<address>(*item).value, 16)); break;
        case OBJECT_VALUE: {
            if (depth >= 64) throw std::runtime_error("Observation object nesting exceeds 64");
            auto block = blocks::of(item);
            if (!block) throw std::runtime_error("Observation encountered an invalid object");
            auto [found, inserted] = objects.emplace(block.get(), objects.size());
            if (!inserted) { payload = "{\"ref\":" + std::to_string(found->second) + '}'; break; }
            std::vector<std::string> slots;
            for (const auto& slot : block->slots) slots.push_back(value(slot, depth + 1));
            payload = "{\"id\":" + std::to_string(found->second) + ",\"state\":" +
                std::to_string(static_cast<int>(block->state)) + ",\"slots\":" + array(slots);
            if (block->buffer) {
                payload += ",\"length\":" + std::to_string(block->buffer->length) +
                    ",\"capacity\":" + std::to_string(block->buffer->capacity);
            }
            payload += '}'; break;
        }
        default: throw std::runtime_error("Observation encountered an unknown value tag");
        }
        return "{\"tag\":" + std::to_string(tag(*item)) + ",\"value\":" + payload + '}';
    }
};

std::string trace() {
    const auto& saved = last_error_trace(); std::vector<std::string> frames;
    for (const auto& frame : saved.frames)
        frames.push_back("{\"function\":" + std::to_string(static_cast<std::uint32_t>(frame.function_id)) +
            ",\"native\":" + (frame.native ? "true" : "false") + ",\"name\":" + quote(frame.name) +
            ",\"file\":" + quote(frame.file) + ",\"line\":" + std::to_string(frame.line) +
            ",\"column\":" + std::to_string(frame.column) + '}');
    return "{\"frames\":" + array(frames) + ",\"omitted\":" + std::to_string(saved.omitted_frames) + '}';
}
std::string failure(const std::exception& error) {
    // Portable public exception families, not implementation-specific typeid names.
    const char* category = dynamic_cast<const std::invalid_argument*>(&error) ? "invalid_argument" :
        dynamic_cast<const std::out_of_range*>(&error) ? "out_of_range" :
        dynamic_cast<const std::overflow_error*>(&error) ? "overflow_error" :
        dynamic_cast<const std::runtime_error*>(&error) ? "runtime_error" : "exception";
    return "{\"category\":" + quote(category) + ",\"message\":" + quote(error.what()) +
        ",\"trace\":" + trace() + '}';
}
std::string state(const std::shared_ptr<script>& script) {
    observation observed; std::vector<std::string> globals, allocations;
    if (script && script->baseEnv)
        for (const auto& item : script->baseEnv->variables) globals.push_back(observed.value(item));
    for (int i = 0; i < heap::lenAlloc(); ++i) {
        const auto allocation = heap::allocAt(i); std::vector<std::string> slots;
        for (int j = 0; j < allocation.len; ++j)
            slots.push_back(observed.value(heap::getAt(address{allocation.startpos.value + static_cast<std::uint64_t>(j)})));
        allocations.push_back("{\"address\":" + quote(hex(allocation.startpos.value, 16)) +
            ",\"length\":" + std::to_string(allocation.len) + ",\"slots\":" + array(slots) + '}');
    }
    return "{\"globals\":" + array(globals) + ",\"heap\":" + array(allocations) +
        ",\"object_records\":" + std::to_string(heap::object_records().size()) +
        ",\"method_tables\":" + std::to_string(heap::method_table_count()) + '}';
}

class callback final : public function {
    std::function<V(std::shared_ptr<environment>, const std::vector<V>&)> body;
public:
    explicit callback(decltype(body) body) : body(std::move(body)) {}
    int return_type() override { return INT_VALUE; }
    V invoke(std::shared_ptr<environment> env, std::vector<V> args) override { return body(std::move(env), args); }
};
class callbacks final : public executor {
    std::map<int, std::shared_ptr<function>> entries;
public:
    explicit callbacks(std::vector<std::string>& events) {
        for (int local = 1; local <= 4; ++local) {
            entries.emplace((host_namespace << 16) | local, std::make_shared<callback>([local, &events](auto env, const auto& args) -> V {
                observation observed; std::vector<std::string> arguments;
                for (const auto& arg : args) arguments.push_back(observed.value(arg));
                events.push_back("{\"callback\":" + std::to_string(local) + ",\"arguments\":" + array(arguments) + '}');
                if (local == 2) throw std::runtime_error("differential host failure");
                auto number = [&](std::size_t index) {
                    if (index >= args.size() || !args[index] || tag(*args[index]) != INT_VALUE)
                        throw std::invalid_argument("Differential callback requires int arguments");
                    return scalar<int>(*args[index]);
                };
                if (local == 1) return std::make_shared<variable>(number(0));
                auto owner = env->script.lock();
                if (!owner) throw std::runtime_error("Differential callback script expired");
                if (local == 3) {
                    const int index = number(0);
                    if (index < 0 || static_cast<std::size_t>(index) >= owner->baseEnv->variables.size())
                        throw std::out_of_range("Differential callback global index");
                    owner->baseEnv->variables[index]->setValue(number(1));
                    return std::make_shared<variable>(number(1));
                }
                auto result = owner->invoke(number(0), {std::make_shared<variable>(number(1))});
                events.push_back("{\"reentry_return\":" + observation{}.value(result) + '}');
                return result;
            }));
        }
    }
    int namespace_name() override { return host_namespace; }
    std::shared_ptr<function> getiFunction(int id) override {
        const auto found = entries.find(id); return found == entries.end() ? nullptr : found->second;
    }
};

V parse_value(const std::string& text) {
    if (text.size() < 2 || text[1] != ':') throw std::invalid_argument("Expected tag:value");
    const auto data = text.substr(2);
    switch (text[0]) {
    case 'i': return std::make_shared<variable>(std::stoi(data));
    case 'b': if (data != "0" && data != "1") throw std::invalid_argument("Invalid boolean");
        return std::make_shared<variable>(data == "1");
    case 'd': return std::make_shared<variable>(std::bit_cast<double>(std::stoull(data, nullptr, 16)));
    case 'f': return std::make_shared<variable>(std::bit_cast<float>(static_cast<std::uint32_t>(std::stoul(data, nullptr, 16))));
    case 'a': return std::make_shared<variable>(address{std::stoull(data, nullptr, 16)});
    case 's': return std::make_shared<variable>(data);
    case 'v': return std::make_shared<variable>(nullptr);
    default: throw std::invalid_argument("Unknown argument tag");
    }
}
}

int main(int argc, char** argv) {
    // Infrastructure errors use exit 2. Script failures are observations (exit 0).
    std::ostringstream output;
    auto* previous = std::cout.rdbuf(output.rdbuf());
    try {
        if (argc < 2) throw std::invalid_argument("Missing module");
        std::uint64_t steps = 1000000; std::size_t depth = 64;
#ifndef AZSCRIPT_VM_REFERENCE_ONLY
        std::size_t workspace_bytes = vm::default_workspace_bytes;
#else
        std::size_t workspace_bytes = 1; // Parsed for a shared command line; unused by the tree build.
#endif
        bool tree = false; std::vector<std::string> inserted;
        int function_id = 0x0fff0000; std::vector<V> arguments;
        std::vector<std::pair<std::size_t, V>> mutations;
        for (int at = 2; at < argc; ++at) {
            const std::string flag = argv[at];
            if (flag == "--tree") { tree = true; continue; }
            if (++at >= argc) throw std::invalid_argument("Missing option value");
            const std::string value = argv[at];
            if (flag == "--steps") steps = std::stoull(value);
            else if (flag == "--call-depth") depth = std::stoull(value);
            else if (flag == "--workspace-bytes") workspace_bytes = std::stoull(value);
            else if (flag == "--insert") inserted.push_back(value);
            else if (flag == "--function") function_id = std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(std::stoull(value, nullptr, 0)));
            else if (flag == "--arg") arguments.push_back(parse_value(value));
            else if (flag == "--set-global") {
                const auto separator = value.find('=');
                if (separator == std::string::npos) throw std::invalid_argument("Expected global=tag:value");
                mutations.emplace_back(std::stoull(value.substr(0, separator)), parse_value(value.substr(separator + 1)));
            } else throw std::invalid_argument("Unknown option: " + flag);
        }
        if (!steps || !depth || !workspace_bytes) throw std::invalid_argument("Budgets must be positive");
        std::ifstream input(argv[1], std::ios::binary);
        if (!input) throw std::runtime_error("Cannot read module");
        std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(input), {}};
        std::vector<std::string> events; registerExecutor(std::make_shared<callbacks>(events));
        std::shared_ptr<script> program; std::string error = "null", returned = "null", phase = "load";
        try {
            program = load_script(bytes.data(), bytes.size()); program->max_steps = steps; program->max_call_depth = depth;
            for (const auto& path : inserted) {
                std::ifstream module(path, std::ios::binary);
                if (!module) throw std::runtime_error("Cannot read inserted module");
                std::vector<unsigned char> more{std::istreambuf_iterator<char>(module), {}};
                program->insert_script(more.data(), more.size());
            }
#ifndef AZSCRIPT_VM_REFERENCE_ONLY
            if (!tree) vm::select_backend(*program, workspace_bytes);
#else
            (void)tree; // This separately built adapter always uses the frozen tree library.
#endif
            phase = "flush"; program->flush();
            for (const auto& [index, value] : mutations) {
                if (index >= program->baseEnv->variables.size()) throw std::out_of_range("Mutation global index");
                program->baseEnv->variables[index]->copy_from(value);
            }
            phase = "invoke"; auto result = program->invoke(function_id, std::move(arguments));
            returned = observation{}.value(result); phase = "complete";
        } catch (const std::exception& caught) { error = failure(caught); }
        const auto before = state(program);
        const auto remaining = program ? program->remaining_steps : 0;
        const bool faulted = program && program->faulted;
        const auto before_events = array(events); const auto before_stdout = quote(bytes_hex(output.str()));
        events.clear(); output.str(""); output.clear();
        std::string teardown_error = "null";
        if (program) try { program->destroy(); } catch (const std::exception& caught) { teardown_error = failure(caught); }
        const auto after = state(program);
        unregisterExecutor(host_namespace);
        const std::string result = "{\"protocol\":1,\"phase\":" + quote(phase) + ",\"return\":" + returned +
            ",\"error\":" + error + ",\"events\":" + before_events + ",\"stdout_hex\":" + before_stdout +
            ",\"state\":" + before + ",\"remaining_steps\":" + std::to_string(remaining) +
            ",\"faulted\":" + (faulted ? "true" : "false") + ",\"teardown\":{\"error\":" + teardown_error +
            ",\"events\":" + array(events) + ",\"stdout_hex\":" + quote(bytes_hex(output.str())) + ",\"state\":" + after + "}}";
        std::cout.rdbuf(previous); std::cout << result << '\n'; return 0;
    } catch (const std::exception& error) {
        std::cout.rdbuf(previous); std::cerr << "Differential host infrastructure error: " << error.what() << '\n'; return 2;
    }
}
