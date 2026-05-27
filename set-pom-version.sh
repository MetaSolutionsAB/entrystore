#!/bin/bash
set -e

[ $# -eq 1 ] && [ -n "$1" ] || { echo "usage: $0 <version>" >&2; exit 1; }

./mvnw versions:set -DnewVersion="$1"

echo "$1" > VERSION.txt
