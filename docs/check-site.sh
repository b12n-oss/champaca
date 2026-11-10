#!/usr/bin/env bash
# Copyright (C) 2026  champaca contributors
#
# SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0

# Assertions the documentation build must satisfy. Run by the shared site
# workflow (jlt-commons/ci-builds) against the freshly built _site, with
# BASE_PATH exported. Locally:
#   jolt site:build && BASE_PATH=/champaca bash docs/check-site.sh

set -euo pipefail
out=_site
base=${BASE_PATH:-/champaca}

test -f "$out/index.html"     || { echo "no homepage generated"; exit 1; }
test -f "$out/404.html"       || { echo "no 404 page generated"; exit 1; }
test -f "$out/css/screen.css" || { echo "static assets missing"; exit 1; }

# The homepage is the README, and every guide page is listed in the guide
# index: a page that stops rendering would otherwise go unnoticed.
grep -q 'Champaca' "$out/index.html" || { echo "homepage has no content"; exit 1; }
for page in docs/guide/*.md; do
  name=$(basename "$page" .md)
  test -f "$out/guide/$name.html" || { echo "guide page $name missing"; exit 1; }
done

# The README's images come from docs/images through :asset-dirs, and its
# warning is a GitHub alert, which the engine renders as a callout from v0.4.4.
for img in logo.svg champaca-1880.jpg; do
  test -f "$out/images/$img" || { echo "image $img not copied"; exit 1; }
  grep -q "src=\"$base/images/$img\"" "$out/index.html" \
    || { echo "homepage doesn't link $img under $base"; exit 1; }
done
grep -q 'markdown-alert-warning' "$out/index.html" \
  || { echo "the README's warning didn't render as a callout"; exit 1; }

! grep -rq '{{site-base}}' "$out" || { echo "unrendered template variable"; exit 1; }

# Served at <owner>.github.io/champaca/, a root-absolute URL outside the base
# path would load the organization site's file instead of this one's.
if grep -rohE '(href|src)="/[^"]*"' "$out" --include='*.html' | grep -v "=\"$base/" | grep -q .; then
  echo "a URL escapes $base:"; grep -rohE '(href|src)="/[^"]*"' "$out" --include='*.html' | grep -v "=\"$base/" | sort -u | head
  exit 1
fi
echo "site checks passed"
