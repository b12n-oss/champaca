#!/bin/bash
# Lays the module path probes out under the directory given:
#   mods/greet.jar (com.greet, a provider and two resources), mods/app/
#   (exploded, requires com.greet, uses the service)
#   mods2/greet.jar, mods2/app.jar (app with ModuleMainClass app.Main)
#   cp/ (cp.Probe naming com.greet.Greeter, cp.Res asking for its resources)
#   split/ (com.greet.Extra on the class path, in com.greet's package)
# Used by bb test and record.sh. JAVA_HOME's javac and jar, else PATH's.
set -e
src="$(cd "$(dirname "$0")" && pwd)/src"
out=$1
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
rm -rf "$out" && mkdir -p "$out/mods" "$out/mods2" "$out/tmp"
"${bin}javac" -d "$out/tmp/com.greet" "$src/com.greet/module-info.java" "$src/com.greet/com/greet/Greeter.java" "$src/com.greet/com/greet/Hi.java" "$src/com.greet/com/greet/open/Marker.java"
cp "$src/com.greet/com/greet/msg.txt" "$out/tmp/com.greet/com/greet/"
cp "$src/com.greet/com/greet/open/o.txt" "$out/tmp/com.greet/com/greet/open/"
cp "$src/com.greet/top.txt" "$out/tmp/com.greet/"
mkdir -p "$out/tmp/com.greet/META-INF" && cp "$src/com.greet/META-INF/greet.txt" "$out/tmp/com.greet/META-INF/"
"${bin}jar" cf "$out/mods/greet.jar" -C "$out/tmp/com.greet" .
cp "$out/mods/greet.jar" "$out/mods2/greet.jar"
"${bin}javac" -p "$out/mods" -d "$out/mods/app" "$src/app/module-info.java" "$src/app/app/Main.java" "$src/app/app/Plain.java" "$src/app/app/Split.java"
"${bin}jar" --create --file "$out/mods2/app.jar" --main-class app.Main -C "$out/mods/app" .
"${bin}javac" -cp "$out/tmp/com.greet" -d "$out/cp" "$src/cp/cp/Probe.java" "$src/cp/cp/Res.java"
# split/: com.greet.Extra, a class path class in com.greet's package
"${bin}javac" -d "$out/split" "$src/split/com/greet/Extra.java"
rm -rf "$out/tmp"
