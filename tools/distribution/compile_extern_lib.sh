#!/bin/sh
# Compile a load_extern_library plugin against this package's shared runtime.
set -eu
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
include=${AZSCRIPT_INCLUDE:-}
libdir=${AZSCRIPT_LIBDIR:-}
if [ -z "$include" ] || [ -z "$libdir" ]; then
    if [ -f "$script_dir/include/azscript/extern_library.hpp" ]; then
        include=${include:-$script_dir/include}
        libdir=${libdir:-$script_dir/lib}
    elif [ -f "$script_dir/../../include/azscript/extern_library.hpp" ]; then
        repo=$(CDPATH= cd -P "$script_dir/../.." && pwd)
        include=${include:-$repo/include}
        if [ -z "$libdir" ]; then
            if [ -e "$repo/build/native/interpreter/libabdInvoker.so" ] || [ -e "$repo/build/native/interpreter/libabdInvoker.dylib" ]; then
                libdir=$repo/build/native/interpreter
            elif [ -e "$repo/build/sanitize/interpreter/libabdInvoker.so" ] || [ -e "$repo/build/sanitize/interpreter/libabdInvoker.dylib" ]; then
                libdir=$repo/build/sanitize/interpreter
            else
                echo 'AzScript: set AZSCRIPT_LIBDIR to the directory containing libabdInvoker.' >&2
                exit 1
            fi
        fi
    else
        echo 'AzScript: cannot find include/azscript. Set AZSCRIPT_INCLUDE and AZSCRIPT_LIBDIR.' >&2
        exit 1
    fi
fi
output=
sources=
while [ $# -gt 0 ]; do
    case "$1" in
        -o)
            shift
            [ $# -gt 0 ] || { echo 'AzScript: -o requires an output stem.' >&2; exit 2; }
            output=$1
            ;;
        --)
            shift
            break
            ;;
        -*)
            echo "AzScript: unknown option $1" >&2
            exit 2
            ;;
        *)
            sources="$sources $1"
            ;;
    esac
    shift
done
for extra in "$@"; do
    sources="$sources $extra"
done
if [ -z "$output" ] || [ -z "$sources" ]; then
    echo 'Usage: compile_extern_lib.sh -o stem source.cpp [more.cpp...]' >&2
    exit 2
fi
case "$(uname -s)" in
    Darwin) suffix=.dylib; shared=-dynamiclib ;;
    MINGW*|MSYS*|CYGWIN*) suffix=.dll; shared=-shared ;;
    *) suffix=.so; shared=-shared ;;
esac
case "$output" in
    *.so|*.dylib|*.dll) target=$output ;;
    *) target=$output$suffix ;;
esac
cxx=${CXX:-c++}
# shellcheck disable=SC2086
"$cxx" -std=c++20 -fPIC $shared -I "$include" $sources -L "$libdir" -labdInvoker -Wl,-rpath,"$libdir" -o "$target"
