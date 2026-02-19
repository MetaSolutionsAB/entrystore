#!/bin/bash

set -euo pipefail

CHECK=false
DOMAIN=""

for arg in "$@"; do
	case "$arg" in
		--with-check) CHECK=true ;;
		-*) echo "Unknown option: $arg" >&2; exit 1 ;;
		*) DOMAIN="$arg" ;;
	esac
done

if [ -z "$DOMAIN" ]; then
	echo "Usage: $0 [--with-check] <domain>" >&2
	exit 1
fi

CONTAINER_NAME="solr-${DOMAIN}"
CORE_NAME="entrystore-core"

if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
	echo "Error: Container '${CONTAINER_NAME}' does not exist. Run solr-start.sh ${DOMAIN} first." >&2
	exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
	echo "Error: Container '${CONTAINER_NAME}' exists but is not running." >&2
	exit 1
fi

CONTAINER_IP=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${CONTAINER_NAME}" 2>/dev/null)
if [ -z "$CONTAINER_IP" ]; then
	echo "Error: Could not determine IP address of container '${CONTAINER_NAME}'." >&2
	exit 1
fi

SOLR_URL="http://${CONTAINER_IP}:8983/solr/${CORE_NAME}"

if [ "$CHECK" = true ]; then
	PING_URL="${SOLR_URL}/admin/ping"
	echo "Checking Solr at ${PING_URL} ..." >&2
	if docker exec "${CONTAINER_NAME}" curl -sf --max-time 5 "${PING_URL}" > /dev/null 2>&1; then
		echo "Solr is reachable." >&2
	else
		echo "Error: Solr is not reachable at ${PING_URL}" >&2
		exit 1
	fi
fi

echo "$SOLR_URL"
