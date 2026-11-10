#!/bin/sh
# Copyright (C) 2026  champaca contributors
# Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
#   Anthony Green <green@moxielogic.com>
#
# SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
# See COPYING and COPYING.CLASSPATH-EXCEPTION.

# Build the jolt commit deps.edn pins (:champaca/jolt) into
# ~/.cache/champaca/jolt/<sha8>/jolt, from a checkout at $JOLT_SRC (default
# ~/dev/jolt, cloned from the pin's URL when missing), and print its path.
# Plain sh with git and make, so it can start a machine that has no jolt yet
# (the Linux container); `jolt jolt` runs this same script.
set -eu

cd "$(dirname "$0")/.."
pin=$(sed -n '/:champaca\/jolt/,/}/p' deps.edn)
url=$(printf '%s\n' "$pin" | sed -n 's/.*:git\/url "\([^"]*\)".*/\1/p')
sha=$(printf '%s\n' "$pin" | sed -n 's/.*:git\/sha "\([0-9a-f]*\)".*/\1/p')
[ -n "$url" ] && [ -n "$sha" ] || { echo "build-jolt: no :champaca/jolt pin in deps.edn" >&2; exit 1; }

dir="$HOME/.cache/champaca/jolt/$(printf %.8s "$sha")"
bin="$dir/jolt"
if [ -x "$bin" ]; then
  echo "already built: $bin" >&2
  echo "$bin"
  exit 0
fi

src="${JOLT_SRC:-$HOME/dev/jolt}"
[ -e "$src" ] || git clone "$url" "$src" >&2
git -C "$src" cat-file -e "$sha^{commit}" 2>/dev/null || git -C "$src" fetch "$url" "$sha" >&2
# the release tags too: jolt's version is git describe over v* tags, so a
# commit without its tag builds as the previous release plus a count, which
# :jolt/min-version refuses. Not forced: a moved rolling tag is left alone.
git -C "$src" fetch "$url" 'refs/tags/v*:refs/tags/v*' >&2 \
  || echo "build-jolt: some tags weren't updated (a moved tag is left as it was)" >&2

wt="$dir/src"
[ -e "$wt" ] || git -C "$src" worktree add --detach "$wt" "$sha" >&2
# borrow the checkout's submodule objects when it has them
git -C "$wt" config -f .gitmodules --get-regexp path | while read -r _ p; do
  if [ -e "$src/$p/.git" ]; then
    git -C "$wt" submodule update --init --reference "$src/$p" "$p" >&2
  else
    git -C "$wt" submodule update --init "$p" >&2
  fi
done
make -C "$wt" build >&2
cp "$wt/target/release/jolt" "$bin"
"$bin" --version >&2
echo "$bin"
