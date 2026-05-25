#!/bin/bash
set -eo pipefail

DEPS=$(./mvnw dependency:list -Dsort=true)

echo "$DEPS" | grep INFO | grep : | grep '   ' | awk '{print $2}' | cut -f1-4 -d: | sort | uniq | cut -f1-3 -d: | uniq -c | grep -v '^ *1 ' || true

echo -e "\nInvestigate dependency with: ./mvnw dependency:tree -Dincludes=groupId:artifactId"
