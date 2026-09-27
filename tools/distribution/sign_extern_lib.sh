#!/bin/sh
# Sign a plugin, or generate the P-256 key pair used by load_extern_library.
set -eu
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
if [ -n "${AZSCRIPT_SIGN_EXTERN:-}" ]; then
    tool=$AZSCRIPT_SIGN_EXTERN
elif [ -x "$script_dir/bin/azscript-sign-extern" ]; then
    tool=$script_dir/bin/azscript-sign-extern
else
    repo=$(CDPATH= cd -P "$script_dir/../.." && pwd)
    if [ -x "$repo/build/native/interpreter/azscript-sign-extern" ]; then
        tool=$repo/build/native/interpreter/azscript-sign-extern
    elif [ -x "$repo/build/sanitize/interpreter/azscript-sign-extern" ]; then
        tool=$repo/build/sanitize/interpreter/azscript-sign-extern
    else
        echo 'AzScript: azscript-sign-extern was not found. Set AZSCRIPT_SIGN_EXTERN.' >&2
        exit 1
    fi
fi
if [ "${1:-}" = "--generate-key" ]; then
    [ $# -eq 3 ] || { echo 'Usage: sign_extern_lib.sh --generate-key private.pem public.pem' >&2; exit 2; }
    exec "$tool" genkey --private "$2" --public "$3"
fi
[ $# -eq 2 ] || { echo 'Usage: sign_extern_lib.sh private.pem library-file' >&2; exit 2; }
exec "$tool" sign --key "$1" --library "$2"
