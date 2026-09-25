#!/bin/sh
set -eu
azscript_root=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
exec "$azscript_root/bin/azscript-run" "$@"
