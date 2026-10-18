#!/bin/sh
# Build only inside this checkout; no brew install, global PATH or launch agent.
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)
pgroot="$project/.runtime/postgres"
version=18.6
sha=555610c24d53e4316da5b7d3fc25c279d96856d5e0e23ee308c328c5fa881d9f
mkdir -p "$pgroot/downloads" "$pgroot/build" "$project/.runtime/tmp"
export TMPDIR="$project/.runtime/tmp"
archive="postgresql-$version.tar.bz2"
cd "$pgroot/downloads"
if [ ! -f "$archive" ]; then
  curl --fail --location --retry 2 "https://ftp.postgresql.org/pub/source/v$version/$archive" -o "$archive"
fi
printf '%s  %s\n' "$sha" "$archive" | shasum -a 256 -c -
if [ ! -d "$pgroot/build/postgresql-$version" ]; then tar -xjf "$archive" -C "$pgroot/build"; fi
cd "$pgroot/build/postgresql-$version"
./configure --prefix="$pgroot/install" --without-icu --without-readline > "$pgroot/configure.log" 2>&1
make -j4 > "$pgroot/build.log" 2>&1
make install > "$pgroot/install.log" 2>&1
"$pgroot/install/bin/postgres" --version
