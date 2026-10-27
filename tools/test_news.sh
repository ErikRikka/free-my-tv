#!/bin/bash
# Runs the plain-JVM tests of the news logic (the parts with no Android in them).
set -euo pipefail
cd "$(dirname "$0")/.."
out=out/news-tests
rm -rf "$out" && mkdir -p "$out"
src=()
for f in NewsFeeds NewsOrder NewsSources; do
    [ -f "app/src/io/freemytv/$f.java" ] && src+=("app/src/io/freemytv/$f.java")
done
javac -encoding UTF-8 -source 8 -target 8 -nowarn -d "$out" ${src[@]+"${src[@]}"} tools/news_tests/*.java 2>&1 | grep -v "bootstrap class path\|^warning\|^1 warning" || true
[ -f "$out/io/freemytv/NewsTests.class" ] || { echo "compile failed"; exit 1; }
java -cp "$out" io.freemytv.NewsTests tools/news_tests
