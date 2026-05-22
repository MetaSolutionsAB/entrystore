#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -f "$PROJECT_ROOT/VERSION.txt" ]; then
  echo "Error: VERSION.txt not found at $PROJECT_ROOT/VERSION.txt" >&2
  exit 1
fi
ENTRYSTORE_VERSION=$(cat "$PROJECT_ROOT/VERSION.txt" | tr -d '[:space:]')
if [ -z "$ENTRYSTORE_VERSION" ]; then
  echo "Error: VERSION.txt is empty" >&2
  exit 1
fi
JAR_FILE="$PROJECT_ROOT/modules/rest/spring-boot/target/entrystore-rest-spring-boot-${ENTRYSTORE_VERSION}-exec.jar"

echo "Building EntryStore project..."
./mvnw -f "$PROJECT_ROOT/pom.xml" clean package -DskipTests

if [ ! -f "$JAR_FILE" ]; then
  echo "Error: Build did not produce expected jar:"
  echo "  $JAR_FILE"
  exit 1
fi

DOCKER_IMAGE_TAG=metasolutions/entrystore:$ENTRYSTORE_VERSION

echo "Building EntryStore $ENTRYSTORE_VERSION with image tag $DOCKER_IMAGE_TAG"

docker build \
  -f "$SCRIPT_DIR/Dockerfile" \
  --no-cache \
  --pull \
  --build-arg ENTRYSTORE_VERSION="$ENTRYSTORE_VERSION" \
  --tag "$DOCKER_IMAGE_TAG" \
  "$PROJECT_ROOT"
