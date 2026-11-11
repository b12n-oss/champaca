#!/bin/sh
# Records HotSpot's output for each test/encoding/<case>.env as
# <case>.<os>.expected, os macos or linux: LANG, LC_ALL and LC_CTYPE are
# cleared, then the case's assignments (one a line) are set, as bb test
# runs champaca. Run from the repository with JDK 25's java on the PATH,
# on each OS (Linux: in the bb linux container, CHAMPACA_LINUX_RUN).
set -e
os=$(uname -s | sed 's/Darwin/macos/; s/Linux/linux/')
mkdir -p target/encoding
javac -d target/encoding test/encoding/Props.java
for f in test/encoding/*.env; do
  c=$(basename "$f" .env)
  env -u LANG -u LC_ALL -u LC_CTYPE $(cat "$f") java -cp target/encoding Props < /dev/null 2> /dev/null > "test/encoding/$c.$os.expected"
done
