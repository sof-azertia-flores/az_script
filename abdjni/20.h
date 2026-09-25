#ifndef h_abd_20
#define h_abd_20
#include "main.h"
inline std::string name2str(int type){
    if(type==azertian::INT_VALUE){
        return "int";
    }
    if(type==azertian::STRING_VALUE){
        return "string";
    }
    if(type==azertian::FLOAT_VALUE){
        return "float";
    }
    if(type==azertian::DOUBLE_VALUE){
        return "double";
    }
    if(type==azertian::BOOLEAN_VALUE){
        return "bool";
    }
    if(type==azertian::VOID_VALUE){
        return "null";
    }
    return "unknown";
}
#endif