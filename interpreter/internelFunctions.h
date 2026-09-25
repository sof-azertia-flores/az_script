#ifndef INTERNELFUNCTIONS_H
#define INTERNELFUNCTIONS_H
#include "library.h"
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
}
#endif
