#!/bin/bash

set -euo pipefail

if [ $# -lt 1 ]; then
	echo "Usage: $0 <domain>" >&2
	exit 1
fi

DOMAIN="$1"
CONTAINER_NAME="solr-${DOMAIN}"
SOLR_PORT="${SOLR_PORT:-8983}"
SOLR_VERSION="${SOLR_VERSION:-9.8.1}"
CORE_NAME="entrystore-core"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF_DIR="${SCRIPT_DIR}/../modules/rest-standalone/integration-test/src/test/resources/solr"

if [ ! -d "$CONF_DIR" ]; then
	echo "Error: Solr config directory not found: ${CONF_DIR}" >&2
	exit 1
fi

if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
	echo "Container '${CONTAINER_NAME}' already exists."
	if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
		echo "Already running on port ${SOLR_PORT}."
	else
		echo "Starting stopped container..."
		docker start "${CONTAINER_NAME}"
	fi
	exit 0
fi

echo "Starting Solr container '${CONTAINER_NAME}' on port ${SOLR_PORT}..."
docker run -d \
	--name "${CONTAINER_NAME}" \
	-p "${SOLR_PORT}:8983" \
	-v "${CONF_DIR}:/${CORE_NAME}/conf/:rw" \
	-e SOLR_MODULES=analysis-extras \
	--health-cmd "curl -f http://localhost:8983/solr/${CORE_NAME}/admin/ping || exit 1" \
	--health-interval 5s \
	--health-timeout 1s \
	--health-retries 10 \
	--health-start-period 2s \
	"solr:${SOLR_VERSION}" \
	solr-precreate "${CORE_NAME}" "/${CORE_NAME}"

echo "Waiting for Solr to become healthy..."
for i in $(seq 1 30); do
	STATUS=$(docker inspect --format='{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>/dev/null || echo "starting")
	if [ "$STATUS" = "healthy" ]; then
		echo "Solr is ready at http://localhost:${SOLR_PORT}/solr/${CORE_NAME}"
		exit 0
	fi
	sleep 1
done

echo "Warning: Solr did not become healthy within 30s. Check: docker logs ${CONTAINER_NAME}" >&2
exit 1
