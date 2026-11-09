#!/bin/bash
# Copyright (C) 2026  champaca contributors
# Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
#   Anthony Green <green@moxielogic.com>
#
# SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
# See COPYING and COPYING.CLASSPATH-EXCEPTION.

# Record HotSpot's output for each <case>.args (one argument a line), with
# this directory's classes compiled into a scratch directory and run from it:
# a line "exit <status>", stdout, a line "--- stderr", stderr.
#   JAVA_HOME=<jdk 25> test/modopts/record.sh [case...]
cd "$(dirname "$0")"
here=$(pwd)
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
cases=("$@")
[ ${#cases[@]} -eq 0 ] && cases=($(ls *.args | sed 's/\.args$//'))
tmp=$(mktemp -d)
"$javac" -d "$tmp/classes" *.java || exit 1
for c in "${cases[@]}"; do
  args=()
  while IFS= read -r l; do args+=("$l"); done < "$c.args"
  (cd "$tmp/classes" && "$java" "${args[@]}" > ../out 2> ../err); st=$?
  { printf 'exit %s\n' "$st"; cat $tmp/out; printf -- '--- stderr\n'; cat $tmp/err; } > "$here/$c.expected"
done
rm -rf "$tmp"
