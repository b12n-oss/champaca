#!/bin/bash
# Record HotSpot's output for each <case>.args, run from this directory:
# a line "exit <status>", stdout, a line "--- stderr", stderr.
#   JAVA=<jdk 25>/bin/java test/srclaunch/record.sh [case...]
cd "$(dirname "$0")"
java=${JAVA:-java}
cases=("$@")
[ ${#cases[@]} -eq 0 ] && cases=($(ls *.args | sed 's/\.args$//'))
tmp=$(mktemp -d)
for c in "${cases[@]}"; do
  args=()
  while IFS= read -r l; do args+=("$l"); done < "$c.args"
  "$java" "${args[@]}" > $tmp/out 2> $tmp/err; st=$?
  { printf 'exit %s\n' "$st"; cat $tmp/out; printf -- '--- stderr\n'; cat $tmp/err; } > "$c.expected"
done
rm -rf "$tmp"
