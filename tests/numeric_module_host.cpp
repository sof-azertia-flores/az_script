#include <azscript/runtime.hpp>
#include "../interpreter/heepalloc.h"

#include <fstream>
#include <bit>
#include <cstdint>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::vector<unsigned char> read(const char* path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("Cannot open module");
    return {std::istreambuf_iterator<char>(input), {}};
}
}

// Exercise independently compiled modules through the public embedding API.
int main(int argc, char** argv) {
    std::shared_ptr<azertian::script> script;
    try {
        if (argc < 2) throw std::runtime_error("Missing initial module");
        auto bytes = read(argv[1]);
        script = azertian::load_script(bytes.data(), bytes.size());
        int at = 2;
        auto argument = [&]() -> const char* {
            if (at >= argc) throw std::runtime_error("Missing command argument");
            return argv[at++];
        };
        auto function_id = [&]() -> int {
            auto value = std::stoll(argument(), nullptr, 0);
            if (value < INT32_MIN || value > UINT32_MAX) throw std::runtime_error("Invalid function ID");
            return std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(value));
        };
        while (at < argc) {
            std::string command = argument();
            if (command == "insert" || command == "reject-insert") {
                bytes = read(argument());
                bool rejected = false;
                try { script->insert_script(bytes.data(), bytes.size()); }
                catch (const std::exception&) {
                    if (command == "insert") throw;
                    rejected = true;
                }
                if (command == "reject-insert") {
                    if (!rejected) throw std::runtime_error("Expected insertion rejection");
                    std::cout << "rejected\n";
                }
            } else if (command == "flush" || command == "reject-flush") {
                bool rejected = false;
                try { script->flush(); }
                catch (const std::exception&) {
                    if (command == "flush") throw;
                    rejected = true;
                }
                if (command == "reject-flush") {
                    if (!rejected) throw std::runtime_error("Expected flush rejection");
                    std::cout << "rejected\n";
                }
            } else if (command == "setup") {
                std::cout << (script->setup ? "ready" : "pending") << '\n';
            } else if (command == "hint") {
                std::cout << script->namespace_for_hint(argument()) << '\n';
            } else if (command == "call" || command == "reject-call" || command == "hint-call") {
                int id;
                if (command == "hint-call") {
                    auto ns = script->namespace_for_hint(argument());
                    auto local = std::stoi(argument(), nullptr, 0);
                    if (local < 0 || local > 65535) throw std::runtime_error("Invalid local ID");
                    id = std::bit_cast<std::int32_t>((static_cast<std::uint32_t>(ns) << 16) | local);
                } else id = function_id();
                int count = std::stoi(argument());
                if (count < 0 || count > 32) throw std::runtime_error("Invalid argument count");
                std::vector<std::shared_ptr<azertian::variable>> parameters;
                for (int i = 0; i < count; ++i)
                    parameters.push_back(std::make_shared<azertian::variable>(std::stoi(argument())));
                if (command == "reject-call") {
                    bool rejected = false;
                    try { script->invoke(id, std::move(parameters)); }
                    catch (const std::exception&) { rejected = true; }
                    if (!rejected) throw std::runtime_error("Expected call rejection");
                    std::cout << "rejected\n";
                } else std::cout << azertian::value_to_string(script->invoke(id, std::move(parameters))) << '\n';
            } else if (command == "span") {
                auto function = std::dynamic_pointer_cast<azertian::ofunction>(
                    script->getFunction(function_id()));
                if (!function)
                    throw std::runtime_error("Expected a script function");
                std::cout << function->global_offset << ':' << function->global_count << '\n';
            } else if (command == "globals") {
                std::cout << script->baseEnv->variables.size() << '\n';
            } else if (command == "step-limit") {
                const auto limit = std::stoull(argument());
                if (limit == 0) throw std::runtime_error("Step limit must be positive");
                script->max_steps = limit;
            } else if (command == "heap-empty") {
                if (azertian::heap::lenAlloc() != 0 || !azertian::heap::object_records().empty())
                    throw std::runtime_error("Expected every allocation and object registration to be released");
                std::cout << "empty\n";
            } else throw std::runtime_error("Unknown host test command");
        }
        script->destroy();
        return 0;
    } catch (const std::exception& error) {
        if (script) { try { script->destroy(); } catch (...) {} }
        std::cerr << error.what() << '\n';
        return 1;
    }
}
