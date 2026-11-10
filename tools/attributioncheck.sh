#!/bin/sh
# Copyright (C) 2026  champaca contributors
#
# SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
# See COPYING and COPYING.CLASSPATH-EXCEPTION.

# Fails when commit messages, or a text such as a pull request description,
# carry an AI assistant's attribution: a Claude-Session trailer, a claude.ai
# session link, a Co-Authored-By line naming Claude, the "Generated with Claude
# Code" footer, or an anthropic.com noreply address. A change is described by
# what it does; who or what typed it isn't part of the record.
#
#   tools/attributioncheck.sh <range>     the commits in a git range (a..b)
#   tools/attributioncheck.sh --text      the text on stdin
set -eu

markers='Claude-Session:|claude\.ai/code/session_|Co-Authored-By:.*[Cc]laude|Generated with \[?Claude Code|noreply@anthropic\.com'

case "${1:-}" in
  "")
    echo "usage: tools/attributioncheck.sh <range> | --text" >&2
    exit 2 ;;
  --text)
    if found=$(grep -Ein "$markers"); then
      echo "attributioncheck: the text carries AI attribution:" >&2
      printf '%s\n' "$found" | sed 's/^/  /' >&2
      exit 1
    fi
    echo "attributioncheck: the text is clean" ;;
  *)
    range=$1
    commits=$(git log --format=%H -E -i --grep="$markers" "$range")
    if [ -n "$commits" ]; then
      echo "attributioncheck: commits in $range carry AI attribution:" >&2
      for c in $commits; do
        echo "  $(git log -1 --format='%h %s' "$c")" >&2
        git log -1 --format=%B "$c" | grep -Ein "$markers" | sed 's/^/      /' >&2
      done
      exit 1
    fi
    echo "attributioncheck: $(git rev-list --count "$range") commits in $range are clean" ;;
esac
