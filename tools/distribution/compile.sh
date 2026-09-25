#!/bin/sh
set -eu
azscript_root=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
if [ -x "$azscript_root/runtime/bin/java" ]; then
    azscript_java="$azscript_root/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    azscript_java="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    azscript_java=java
else
    echo 'AzScript: Java 17 or newer is required; set JAVA_HOME or use a package with runtime/.' >&2
    exit 127
fi
azscript_lib="$azscript_root/compiler/lib"
azscript_classpath="$azscript_lib/launcher.jar:$azscript_lib/compiler.jar:$azscript_lib/abdJava.jar:$azscript_lib/gson-2.11.0.jar"
exec "$azscript_java" -cp "$azscript_classpath" azertia.distribution.Compile "$@"
