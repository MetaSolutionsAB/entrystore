#!/bin/bash

set -euo pipefail

if [ $# -lt 1 ]; then
	echo "Usage: $0 <domain>" >&2
	exit 1
fi

DOMAIN="$1"
CONTAINER_NAME="solr-${DOMAIN}"
SOLR_PORT="${SOLR_PORT:-8983}"
SOLR_VERSION="${SOLR_VERSION:-10.0.0}"
SOLR_DATA="${SOLR_DATA:-/srv/${DOMAIN}/data/solr}"
SOLR_MEMORY="${SOLR_MEMORY:-512m}"
CORE_NAME="entrystore-core"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF_DIR="${SCRIPT_DIR}/../modules/rest/integration-test/src/test/resources/solr"

if [ ! -d "$CONF_DIR" ]; then
	echo "Error: Solr config directory not found: ${CONF_DIR}" >&2
	exit 1
fi

if [ ! -d "$SOLR_DATA" ]; then
	echo "Creating Solr data directory ${SOLR_DATA}..."
	mkdir -p "${SOLR_DATA}"
	if [ "$(id -u)" = "0" ]; then
		chown 8983:8983 "${SOLR_DATA}"
	else
		echo "Warning: Not running as root, cannot chown ${SOLR_DATA} to UID 8983. Solr may fail to write data." >&2
	fi
else
	OWNER_UID=$(stat -c '%u' "${SOLR_DATA}" 2>/dev/null || stat -f '%u' "${SOLR_DATA}" 2>/dev/null || echo "unknown")
	if [ "$OWNER_UID" != "8983" ] && [ "$OWNER_UID" != "unknown" ]; then
		echo "Warning: ${SOLR_DATA} is owned by UID ${OWNER_UID}, expected 8983 (solr). Solr may fail to write data." >&2
	fi
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

echo "Starting Solr container '${CONTAINER_NAME}' on port ${SOLR_PORT} (memory: ${SOLR_MEMORY})..."
docker run -d \
	--name "${CONTAINER_NAME}" \
	--restart unless-stopped \
	--memory "${SOLR_MEMORY}" \
	-p "127.0.0.1:${SOLR_PORT}:8983" \
	-v "${SOLR_DATA}:/var/solr/data" \
	-v "${CONF_DIR}:/${CORE_NAME}/conf/:rw" \
	-e SOLR_JAVA_MEM="-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=80.0" \
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
	if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
		echo "Error: Container '${CONTAINER_NAME}' no longer exists. It may have crashed." >&2
		exit 1
	fi
	STATUS=$(docker inspect --format='{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>&1) || {
		echo "Error: Failed to inspect container: $STATUS" >&2
		exit 1
	}
	if [ "$STATUS" = "healthy" ]; then
		echo "Solr is ready at http://localhost:${SOLR_PORT}/solr/${CORE_NAME}"
		exit 0
	fi
	if [ "$STATUS" = "unhealthy" ]; then
		echo "Error: Solr container is unhealthy. Check: docker logs ${CONTAINER_NAME}" >&2
		exit 1
	fi
	sleep 1
done

echo "Warning: Solr did not become healthy within 30s. Check: docker logs ${CONTAINER_NAME}" >&2
exit 1
