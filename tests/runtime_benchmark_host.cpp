// The same source is built against the frozen and candidate runtimes. Timings
// include the public invoke boundary; compilation and file I/O are separate.
#include <azscript/runtime.hpp>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#if defined(__unix__) || defined(__APPLE__)
#include <sys/resource.h>
#endif
#ifndef AZSCRIPT_BENCHMARK_BUILD_TYPE
#define AZSCRIPT_BENCHMARK_BUILD_TYPE "unknown"
#endif

namespace {
using Clock = std::chrono::steady_clock;
// R04 typed API on the candidate; the identical harness also builds against
// the frozen pre-migration oracle. Only that legacy instantiation reads fields.
template<class T> int tag(const T& value) {
    if constexpr (requires { value.type_tag(); }) return value.type_tag();
    else return value.type;
}
template<class T> int integer_value(const T& value) {
    if constexpr (requires { value.template get<int>(); }) return value.template get<int>();
    else return *static_cast<const int*>(value.value);
}
struct Timing { double wall = 0, cpu = 0; };
struct Start {
    Clock::time_point wall = Clock::now();
    std::clock_t cpu = std::clock();
    Timing end() const {
        return {std::chrono::duration<double>(Clock::now() - wall).count(),
                static_cast<double>(std::clock() - cpu) / CLOCKS_PER_SEC};
    }
};
void write_quoted(const std::string& value) {
    std::cout << '"';
    for (unsigned char ch : value) {
        if (ch == '"' || ch == '\\') std::cout << '\\' << ch;
        else if (ch < 0x20) {
            const char hex[] = "0123456789abcdef";
            std::cout << "\\u00" << hex[ch >> 4] << hex[ch & 15];
        } else std::cout << ch;
    }
    std::cout << '"';
}
void timing(const Timing& value) {
    std::cout << "{\"wall_seconds\":" << value.wall << ",\"cpu_seconds\":" << value.cpu << '}';
}
std::uint64_t integer(const std::string& text) {
    if (text.empty() || text.front() == '-') throw std::invalid_argument("Expected a nonnegative integer");
    std::size_t used = 0;
    const auto result = std::stoull(text, &used, 0);
    if (used != text.size()) throw std::invalid_argument("Invalid integer argument");
    return result;
}
int signed_integer(const std::string& text) {
    std::size_t used = 0;
    const auto result = std::stoll(text, &used, 0);
    if (used != text.size() || result < INT32_MIN || result > INT32_MAX)
        throw std::invalid_argument("Expected an int32 value");
    return static_cast<int>(result);
}
std::vector<unsigned char> read(const std::string& path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) throw std::runtime_error("Cannot open input: " + path);
    const auto size = input.tellg();
    if (size < 4 || size > 64 * 1024 * 1024) throw std::runtime_error("Invalid input size");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(size));
    input.seekg(0); input.read(reinterpret_cast<char*>(bytes.data()), size);
    if (!input) throw std::runtime_error("Incomplete input read");
    return bytes;
}
std::uint64_t checksum(const std::vector<int>& values, int expected) {
    std::uint64_t hash = 14695981039346656037ULL;
    for (int value : values) {
        if (value != expected) throw std::runtime_error("Benchmark result differs from the independent oracle");
        const auto bits = std::bit_cast<std::uint32_t>(value);
        for (int i = 0; i < 4; ++i) { hash ^= (bits >> (8 * i)) & 255; hash *= 1099511628211ULL; }
    }
    return hash;
}
long long peak_rss() {
#if defined(__unix__) || defined(__APPLE__)
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) return -1;
#if defined(__APPLE__)
    return usage.ru_maxrss;
#else
    return static_cast<long long>(usage.ru_maxrss) * 1024;
#endif
#else
    return -1;
#endif
}
}

int main(int argc, char** argv) {
    Start entered;
    std::shared_ptr<azertian::script> script;
    std::string phase = "arguments";
    try {
        std::string path;
        int argument = 0, expected = 0, id = 0x0fff0002;
        bool has_expected = false;
        std::size_t calls = 1, batches = 5, warmup = 3, depth = 256, stack = 0;
        std::uint64_t steps = 10000000;
        for (int at = 1; at < argc; ++at) {
            const std::string option = argv[at];
            if (++at >= argc) throw std::invalid_argument("Missing option value");
            const std::string value = argv[at];
            if (option == "--script") path = value;
            else if (option == "--argument") argument = signed_integer(value);
            else if (option == "--expected") { expected = signed_integer(value); has_expected = true; }
            else if (option == "--function") {
                const auto bits = integer(value);
                if (bits > UINT32_MAX) throw std::invalid_argument("Function ID exceeds uint32");
                id = std::bit_cast<int>(static_cast<std::uint32_t>(bits));
            } else if (option == "--calls") calls = integer(value);
            else if (option == "--batches") batches = integer(value);
            else if (option == "--warmup") warmup = integer(value);
            else if (option == "--max-steps") steps = integer(value);
            else if (option == "--max-depth") depth = integer(value);
            else if (option == "--max-stack-bytes") stack = integer(value);
            else throw std::invalid_argument("Unknown benchmark option: " + option);
        }
        if (path.empty() || !has_expected || !calls || calls > 1000000 || !batches || batches > 1000 ||
            warmup > 1000000 || !steps || !depth || depth > 1000000)
            throw std::invalid_argument("Missing or excessive benchmark arguments");
        phase = "file_read";
        Start read_start; auto bytes = read(path); const auto read_time = read_start.end();
        phase = "load";
        Start load_start; script = azertian::load_script(bytes.data(), bytes.size()); const auto load_time = load_start.end();
        script->max_steps = steps; script->max_call_depth = depth; script->max_stack_bytes = stack;
        phase = "flush";
        Start flush_start; script->flush(); const auto flush_time = flush_start.end();
        std::vector<std::shared_ptr<azertian::variable>> parameters{std::make_shared<azertian::variable>(argument)};
        auto invoke = [&] {
            auto value = script->invoke(id, parameters);
            if (!value || tag(*value) != azertian::INT_VALUE) throw std::runtime_error("Benchmark must return int");
            return integer_value(*value);
        };
        phase = "first_invoke";
        Start first_start; const int first = invoke(); const auto first_time = first_start.end();
        const auto cold_time = entered.end();
        checksum({first}, expected);
        phase = "warmup";
        Start warmup_start;
        for (std::size_t i = 0; i < warmup; ++i) if (invoke() != expected) throw std::runtime_error("Warmup result mismatch");
        const auto warmup_time = warmup_start.end();
        std::vector<int> values(calls);
        std::vector<Timing> times; times.reserve(batches);
        std::vector<std::uint64_t> hashes; hashes.reserve(batches);
        phase = "steady";
        for (std::size_t batch = 0; batch < batches; ++batch) {
            Start start;
            for (auto& value : values) value = invoke();
            times.push_back(start.end());
            // Validation and checksum generation are outside the timed batch.
            hashes.push_back(checksum(values, expected));
        }
        phase = "destroy";
        Start destroy_start; script->destroy(); script.reset(); const auto destroy_time = destroy_start.end();
        std::cout << std::setprecision(17) << "{\"schema_version\":1,\"ok\":true,\"build_type\":";
        write_quoted(AZSCRIPT_BENCHMARK_BUILD_TYPE);
        std::cout << ",\"compiler\":";
#ifdef __VERSION__
        write_quoted(__VERSION__);
#else
        write_quoted("unknown");
#endif
        std::cout << ",\"ndebug\":";
#ifdef NDEBUG
        std::cout << "true";
#else
        std::cout << "false";
#endif
        std::cout << ",\"calls_per_batch\":" << calls << ",\"warmup_calls\":" << warmup
                  << ",\"argument\":" << argument << ",\"expected\":" << expected << ",\"first_result\":" << first
                  << ",\"max_steps\":" << steps << ",\"max_call_depth\":" << depth << ",\"max_stack_bytes\":" << stack
                  << ",\"peak_rss_bytes\":" << peak_rss() << ",\"phases\":{\"file_read\":";
        timing(read_time); std::cout << ",\"load\":"; timing(load_time);
        std::cout << ",\"flush\":"; timing(flush_time);
        std::cout << ",\"first_invoke\":"; timing(first_time);
        std::cout << ",\"cold_to_first_invoke\":"; timing(cold_time);
        std::cout << ",\"warmup\":"; timing(warmup_time);
        std::cout << ",\"destroy\":"; timing(destroy_time);
        std::cout << "},\"batches\":[";
        for (std::size_t i = 0; i < times.size(); ++i) {
            if (i) std::cout << ',';
            std::cout << "{\"timing\":"; timing(times[i]);
            // Strings preserve the complete uint64 checksum in JSON consumers.
            std::cout << ",\"checksum\":\"" << hashes[i] << "\"}";
        }
        std::cout << "]}\n";
        return 0;
    } catch (const std::exception& error) {
        const auto message = azertian::describe_error(error);
        if (script && !script->closed) try { script->destroy(); } catch (...) {}
        std::cout << "{\"schema_version\":1,\"ok\":false,\"phase\":"; write_quoted(phase);
        std::cout << ",\"error\":"; write_quoted(message); std::cout << "}\n";
        return 1;
    }
}
