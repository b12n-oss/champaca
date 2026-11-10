#!/bin/bash
# Copyright (C) 2026  champaca contributors
# Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
#   Anthony Green <green@moxielogic.com>
#
# SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
# See COPYING and COPYING.CLASSPATH-EXCEPTION.

# Lays the module path probes out under the directory given:
#   mods/greet.jar (com.greet, a provider and two resources), mods/app/
#   (exploded, requires com.greet, uses the service)
#   mods2/greet.jar, mods2/app.jar (app with ModuleMainClass app.Main)
#   cp/ (cp.Probe naming com.greet.Greeter, cp.Res asking for its resources)
#   split/ (com.greet.Extra on the class path, in com.greet's package)
#   mods3/ (seven modules with META-INF/x, and om requiring them)
# Used by jolt test and record.sh. JAVA_HOME's javac and jar, else PATH's.
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
mkdir -p "$out/split/com/greet/open" && cp "$src/split/com/greet/open/extra.txt" "$out/split/com/greet/open/"
# mods3/: seven modules each with META-INF/x holding its name, and om, which
# requires them all (names whose order in the loader's map is stable: q1 and
# zeta share a bucket, and HotSpot lists them either way round)
mkdir -p "$out/mods3"
for m in alpha zeta mid bb.cc r2 omega kappa; do
  mkdir -p "$out/tmp/$m/META-INF" && printf 'module %s { }\n' "$m" > "$out/tmp/$m.java.src"
  mkdir -p "$out/tmp/src-$m" && cp "$out/tmp/$m.java.src" "$out/tmp/src-$m/module-info.java"
  "${bin}javac" -Xlint:-module -d "$out/tmp/$m" "$out/tmp/src-$m/module-info.java"
  printf '%s\n' "$m" > "$out/tmp/$m/META-INF/x"
  "${bin}jar" cf "$out/mods3/$m.jar" -C "$out/tmp/$m" .
done
"${bin}javac" -p "$out/mods3" -d "$out/mods3/om" "$src/om/module-info.java" "$src/om/om/Main.java"
rm -rf "$out/tmp"
