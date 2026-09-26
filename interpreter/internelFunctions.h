#ifndef INTERNELFUNCTIONS_H
#define INTERNELFUNCTIONS_H
#include "library.h"
#include <cstdint>
#include <string_view>
namespace azertian {
class executor {
public:
    virtual ~executor()=default;
    virtual int namespace_name()=0;
    virtual std::shared_ptr<function> getiFunction(int id)=0;
};
bool isInternelFunction(int id);
std::shared_ptr<function> getiFunction(int id);
void registerExecutor(std::shared_ptr<executor> executor);
void unregisterExecutor(int namespace_id);
std::set<int> registered_executor_namespaces();
// Increases on every successful registerExecutor or unregisterExecutor call.
std::uint64_t executor_registry_generation();
// PEM text may contain several PUBLIC KEY blocks. Private keys are rejected.
void add_trusted_public_key_pem(std::string_view pem);
void clear_trusted_public_keys();
std::size_t trusted_public_key_count();
}
#endif
