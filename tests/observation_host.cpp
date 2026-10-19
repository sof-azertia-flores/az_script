// Engine-neutral runtime observation host, protocol 2. One source is built per
// runtime under test (the frozen reference and every candidate); no observation
// field is normalized. Protocol 2 extends the R03 protocol with probes sampled
// inside host callbacks (call depth, remaining budget, published trace), post-run
// probes of process-global object state (next block id, first-fit position, the
// global scope's owned list, object records) and the engine the runtime reports.
#include <azscript/runtime.hpp>
#include "../interpreter/vm/session.h"
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
#ifndef _WIN32
#include <pthread.h>
#endif

namespace {
using namespace azertian;
using V = std::shared_ptr<variable>;
constexpr int host_namespace = 0x6dff;

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
        switch (item->type_tag()) {
        case INT_VALUE: payload = std::to_string(item->get<int>()); break;
        case STRING_VALUE: payload = quote(bytes_hex(item->get<std::string>())); break;
        case FLOAT_VALUE: payload = quote(hex(std::bit_cast<std::uint32_t>(item->get<float>()), 8)); break;
        case DOUBLE_VALUE: payload = quote(hex(std::bit_cast<std::uint64_t>(item->get<double>()), 16)); break;
        case BOOLEAN_VALUE: payload = item->get<bool>() ? "true" : "false"; break;
        case VOID_VALUE: payload = "null"; break;
        case ADDRESS_VALUE: payload = quote(hex(item->get<address>().value, 16)); break;
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
        return "{\"tag\":" + std::to_string(item->type_tag()) + ",\"value\":" + payload + '}';
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
        dynamic_cast<const std::logic_error*>(&error) ? "logic_error" :
        dynamic_cast<const std::runtime_error*>(&error) ? "runtime_error" : "exception";
    return "{\"category\":" + quote(category) + ",\"message\":" + quote(error.what()) +
        ",\"trace\":" + trace() + ",\"described\":" + quote(describe_error(error)) + '}';
}

// Post-run probes of process-global state. Each probe consumes one block id and
// allocates then frees one heap slot; both engines run the same probes at the
// same points, so the perturbation is identical and itself compared.
std::string probes() {
    std::string next = "null", fit = "null";
    try { next = quote(hex(blocks::address_of(blocks::create(0)).value, 16)); }
    catch (const std::exception& error) { next = failure(error); }
    try {
        const auto start = heap::alloc(1);
        fit = quote(hex(start.value, 16));
        if (start.value) heap::free(start);
    } catch (const std::exception& error) { fit = failure(error); }
    return "{\"next_block\":" + next + ",\"first_fit\":" + fit + '}';
}
std::string state(const std::shared_ptr<script>& script) {
    observation observed; std::vector<std::string> globals, allocations, owned, objects;
    if (script && script->baseEnv) {
        for (const auto& item : script->baseEnv->variables) globals.push_back(observed.value(item));
        for (const auto entry : script->baseEnv->owned_pointer) owned.push_back(quote(hex(entry.value, 16)));
    }
    for (int i = 0; i < heap::lenAlloc(); ++i) {
        const auto allocation = heap::allocAt(i); std::vector<std::string> slots;
        for (int j = 0; j < allocation.len; ++j)
            slots.push_back(observed.value(heap::getAt(address{allocation.startpos.value + static_cast<std::uint64_t>(j)})));
        allocations.push_back("{\"address\":" + quote(hex(allocation.startpos.value, 16)) +
            ",\"length\":" + std::to_string(allocation.len) + ",\"slots\":" + array(slots) + '}');
    }
    const auto records = heap::object_records();
    for (const auto& record : records)
        objects.push_back("{\"address\":" + quote(hex(record.startpos.value, 16)) +
            ",\"manual\":" + (record.manual ? "true" : "false") + ",\"destructor\":" +
            (record.destructor_id ? std::to_string(static_cast<std::uint32_t>(*record.destructor_id)) : "null") +
            ",\"contexts\":" + std::to_string(record.destructor_contexts.size()) +
            ",\"methods\":" + std::to_string(record.methods ? record.methods->size() : 0) + '}');
    return "{\"globals\":" + array(globals) + ",\"heap\":" + array(allocations) +
        ",\"object_records\":" + std::to_string(records.size()) + ",\"objects\":" + array(objects) +
        ",\"method_tables\":" + std::to_string(heap::method_table_count()) +
        ",\"owned\":" + array(owned) + ",\"probe\":" + probes() + '}';
}

class callback final : public function {
    std::function<V(std::shared_ptr<environment>, const std::vector<V>&)> body;
public:
    explicit callback(decltype(body) body) : body(std::move(body)) {}
    int return_type() override { return INT_VALUE; }
    V invoke(std::shared_ptr<environment> env, std::vector<V> args) override { return body(std::move(env), args); }
};
// 1 records its arguments and returns the first; 2 throws; 3 writes a global;
// 4 re-enters the script; 5 records call depth, budget and published trace.
class callbacks final : public executor {
    std::map<int, std::shared_ptr<function>> entries;
public:
    explicit callbacks(std::vector<std::string>& events) {
        for (int local = 1; local <= 5; ++local) {
            entries.emplace((host_namespace << 16) | local, std::make_shared<callback>([local, &events](std::shared_ptr<environment> env, const std::vector<V>& args) -> V {
                observation observed; std::vector<std::string> arguments;
                for (const auto& arg : args) arguments.push_back(observed.value(arg));
                auto owner = env ? env->script.lock() : nullptr;
                if (local == 5) {
                    if (!owner) throw std::runtime_error("Differential callback script expired");
                    events.push_back("{\"callback\":5,\"arguments\":" + array(arguments) +
                        ",\"active_calls\":" + std::to_string(owner->active_calls) +
                        ",\"remaining_steps\":" + std::to_string(owner->remaining_steps) +
                        ",\"published_trace\":" + trace() + '}');
                } else {
                    events.push_back("{\"callback\":" + std::to_string(local) + ",\"arguments\":" + array(arguments) + '}');
                }
                if (local == 2) throw std::runtime_error("differential host failure");
                auto number = [&](std::size_t index) {
                    if (index >= args.size() || !args[index] || args[index]->type_tag() != INT_VALUE)
                        throw std::invalid_argument("Differential callback requires int arguments");
                    return args[index]->get<int>();
                };
                if (local == 1 || local == 5) return std::make_shared<variable>(number(0));
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

struct options {
    std::string module;
    std::uint64_t steps = 1000000; std::size_t depth = 64, stack_bytes = 0, thread_stack_bytes = 0;
    std::size_t workspace_bytes = vm::default_workspace_bytes;
    bool vm = false; std::vector<std::string> inserted;
    int function_id = 0x0fff0000; std::vector<V> arguments;
    std::vector<std::pair<std::size_t, V>> mutations;
};

options parse(int argc, char** argv) {
    options result;
    if (argc < 2) throw std::invalid_argument("Missing module");
    result.module = argv[1];
    for (int at = 2; at < argc; ++at) {
        const std::string flag = argv[at];
        if (++at >= argc) throw std::invalid_argument("Missing option value");
        const std::string value = argv[at];
        if (flag == "--engine") {
            if (value != "tree" && value != "vm") throw std::invalid_argument("Engine must be tree or vm");
            result.vm = value == "vm";
        } else if (flag == "--steps") result.steps = std::stoull(value);
        else if (flag == "--call-depth") result.depth = std::stoull(value);
        else if (flag == "--stack-bytes") result.stack_bytes = std::stoull(value);
        else if (flag == "--thread-stack-bytes") result.thread_stack_bytes = std::stoull(value);
        else if (flag == "--workspace-bytes") result.workspace_bytes = std::stoull(value);
        else if (flag == "--insert") result.inserted.push_back(value);
        else if (flag == "--function") result.function_id = std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(std::stoull(value, nullptr, 0)));
        else if (flag == "--arg") result.arguments.push_back(parse_value(value));
        else if (flag == "--set-global") {
            const auto separator = value.find('=');
            if (separator == std::string::npos) throw std::invalid_argument("Expected global=tag:value");
            result.mutations.emplace_back(std::stoull(value.substr(0, separator)), parse_value(value.substr(separator + 1)));
        } else throw std::invalid_argument("Unknown option: " + flag);
    }
    if (!result.steps || !result.workspace_bytes) throw std::invalid_argument("Budgets must be positive");
    return result;
}

std::vector<unsigned char> read_file(const std::string& path, const char* what) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error(std::string("Cannot read ") + what);
    return {std::istreambuf_iterator<char>(input), {}};
}

// Script failures are observations; only infrastructure errors throw out.
std::string observe(options& run, std::ostringstream& output) {
    auto bytes = read_file(run.module, "module");
    std::vector<std::string> events; registerExecutor(std::make_shared<callbacks>(events));
    std::shared_ptr<script> program; std::string error = "null", returned = "null", phase = "load";
    try {
        program = load_script(bytes.data(), bytes.size()); program->max_steps = run.steps;
        program->max_call_depth = run.depth; program->max_stack_bytes = run.stack_bytes;
        for (const auto& path : run.inserted) {
            auto more = read_file(path, "inserted module");
            program->insert_script(more.data(), more.size());
        }
        if (run.vm) vm::select_backend(*program, run.workspace_bytes);
        phase = "flush"; program->flush();
        for (const auto& [index, value] : run.mutations) {
            if (index >= program->baseEnv->variables.size()) throw std::out_of_range("Mutation global index");
            program->baseEnv->variables[index]->copy_from(value);
        }
        phase = "invoke"; auto result = program->invoke(run.function_id, std::move(run.arguments));
        returned = observation{}.value(result); phase = "complete";
    } catch (const std::exception& caught) { error = failure(caught); }
    // The runtime, not the command line, reports which engine executed.
    const char* engine = !program ? "unloaded" : vm::workspace_usage(*program).limit_bytes ? "vm" : "tree";
    const auto before = state(program);
    const auto remaining = program ? program->remaining_steps : 0;
    const bool faulted = program && program->faulted;
    const auto before_events = array(events); const auto before_stdout = quote(bytes_hex(output.str()));
    events.clear(); output.str(""); output.clear();
    std::string teardown_error = "null";
    if (program) try { program->destroy(); } catch (const std::exception& caught) { teardown_error = failure(caught); }
    const auto after = state(program);
    unregisterExecutor(host_namespace);
    return "{\"protocol\":2,\"engine\":" + quote(engine) + ",\"phase\":" + quote(phase) + ",\"return\":" + returned +
        ",\"error\":" + error + ",\"events\":" + before_events + ",\"stdout_hex\":" + before_stdout +
        ",\"state\":" + before + ",\"remaining_steps\":" + std::to_string(remaining) +
        ",\"faulted\":" + (faulted ? "true" : "false") + ",\"teardown\":{\"error\":" + teardown_error +
        ",\"events\":" + array(events) + ",\"stdout_hex\":" + quote(bytes_hex(output.str())) + ",\"state\":" + after + "}}";
}

struct job {
    options* run; std::ostringstream* output; std::string result, infrastructure_error;
};
void execute(job& work) {
    try { work.result = observe(*work.run, *work.output); }
    catch (const std::exception& error) { work.infrastructure_error = error.what(); }
}
#ifndef _WIN32
void* thread_entry(void* data) { execute(*static_cast<job*>(data)); return nullptr; }
#endif
}

int main(int argc, char** argv) {
    // Infrastructure errors use exit 2. Script failures are observations (exit 0).
    std::ostringstream output;
    auto* previous = std::cout.rdbuf(output.rdbuf());
    try {
        auto run = parse(argc, argv);
        job work{&run, &output, {}, {}};
        if (run.thread_stack_bytes) {
            // Native-stack differences are validated by re-running on a large stack.
#ifdef _WIN32
            throw std::invalid_argument("--thread-stack-bytes is not supported on Windows");
#else
            pthread_attr_t attributes;
            if (pthread_attr_init(&attributes)) throw std::runtime_error("pthread_attr_init failed");
            pthread_t thread;
            const bool configured = !pthread_attr_setstacksize(&attributes, run.thread_stack_bytes);
            const bool started = configured && !pthread_create(&thread, &attributes, thread_entry, &work);
            pthread_attr_destroy(&attributes);
            if (!started) throw std::runtime_error("Cannot start observation thread with the requested stack");
            pthread_join(thread, nullptr);
#endif
        } else {
            execute(work);
        }
        if (!work.infrastructure_error.empty()) throw std::runtime_error(work.infrastructure_error);
        std::cout.rdbuf(previous); std::cout << work.result << '\n'; return 0;
    } catch (const std::exception& error) {
        std::cout.rdbuf(previous); std::cerr << "Observation host infrastructure error: " << error.what() << '\n'; return 2;
    }
}
