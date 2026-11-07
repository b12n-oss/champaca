#!/bin/bash
# Lays the module path probes out under the directory given:
#   mods/greet.jar (com.greet, a provider and two resources), mods/app/
#   (exploded, requires com.greet, uses the service)
#   mods2/greet.jar, mods2/app.jar (app with ModuleMainClass app.Main)
#   cp/ (cp.Probe, a class path class naming com.greet.Greeter)
# Used by bb test and record.sh. JAVA_HOME's javac and jar, else PATH's.
set -e
src="$(cd "$(dirname "$0")" && pwd)/src"
out=$1
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
rm -rf "$out" && mkdir -p "$out/mods" "$out/mods2" "$out/tmp"
"${bin}javac" -d "$out/tmp/com.greet" "$src/com.greet/module-info.java" "$src/com.greet/com/greet/Greeter.java" "$src/com.greet/com/greet/Hi.java"
cp "$src/com.greet/com/greet/msg.txt" "$out/tmp/com.greet/com/greet/"
cp "$src/com.greet/top.txt" "$out/tmp/com.greet/"
"${bin}jar" cf "$out/mods/greet.jar" -C "$out/tmp/com.greet" .
cp "$out/mods/greet.jar" "$out/mods2/greet.jar"
"${bin}javac" -p "$out/mods" -d "$out/mods/app" "$src/app/module-info.java" "$src/app/app/Main.java"
"${bin}jar" --create --file "$out/mods2/app.jar" --main-class app.Main -C "$out/mods/app" .
"${bin}javac" -cp "$out/tmp/com.greet" -d "$out/cp" "$src/cp/cp/Probe.java"
rm -rf "$out/tmp"
