#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ENTRYSTORE_VERSION=$(cat "$PROJECT_ROOT/VERSION.txt")
JAR_FILE="$PROJECT_ROOT/modules/rest-standalone/spring-boot/target/entrystore-rest-standalone-spring-boot-${ENTRYSTORE_VERSION}-exec.jar"

if [ ! -f "$JAR_FILE" ]; then
  echo "Error: EntryStore has not been built yet. Expected jar not found:"
  echo "  $JAR_FILE"
  echo ""
  echo "Build the project first by running:"
  echo "  mvn clean package -DskipTests"
  exit 1
fi

DOCKER_IMAGE_TAG=metasolutions/entrystore:$ENTRYSTORE_VERSION

echo "Building EntryStore $ENTRYSTORE_VERSION with image tag $DOCKER_IMAGE_TAG"

docker build \
  -f "$SCRIPT_DIR/Dockerfile" \
  --build-arg ENTRYSTORE_VERSION="$ENTRYSTORE_VERSION" \
  --tag "$DOCKER_IMAGE_TAG" \
  "$PROJECT_ROOT"
