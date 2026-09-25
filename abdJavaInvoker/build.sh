#!/bin/sh
# Compile the embedding JAR using only the installed JDK; no network or Gradle cache.
set -eu
bridge_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bridge_output="$bridge_root/build"
mkdir -p "$bridge_output/classes" "$bridge_output/libs"
cd "$bridge_root"
find src/main/java -name '*.java' -print > "$bridge_output/java-sources.txt"
if [ -n "${JAVA_HOME:-}" ]; then
    bridge_javac="$JAVA_HOME/bin/javac"
    bridge_jar="$JAVA_HOME/bin/jar"
else
    bridge_javac=javac
    bridge_jar=jar
fi
"$bridge_javac" --release 17 -encoding UTF-8 -d "$bridge_output/classes" @"$bridge_output/java-sources.txt"
"$bridge_jar" --create --file "$bridge_output/libs/abdJavaInvoker-2.0.0.jar" -C "$bridge_output/classes" .
printf '%s\n' "$bridge_output/libs/abdJavaInvoker-2.0.0.jar"
