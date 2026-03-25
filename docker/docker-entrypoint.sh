#!/bin/sh

set -e

CONFIG_FILE="/srv/entrystore/entrystore.properties"

if [ -n "$ENTRYSTORE_CONFIG_URI" ]; then
  case "$ENTRYSTORE_CONFIG_URI" in
    http://*|https://*)
      echo "Downloading configuration from $ENTRYSTORE_CONFIG_URI"
      wget -q -O "$CONFIG_FILE" --timeout=10 --tries=3 "$ENTRYSTORE_CONFIG_URI"
      ;;
    file://*)
      CONFIG_PATH="${ENTRYSTORE_CONFIG_URI#file://}"
      echo "Loading configuration from $CONFIG_PATH"
      cp "$CONFIG_PATH" "$CONFIG_FILE"
      ;;
    *)
      echo "Loading configuration from $ENTRYSTORE_CONFIG_URI"
      cp "$ENTRYSTORE_CONFIG_URI" "$CONFIG_FILE"
      ;;
  esac
  echo "Configuration loaded successfully"
fi

exec java -jar /srv/entrystore/entrystore.jar \
  --spring.config.import=optional:file://"$CONFIG_FILE" \
  "$@"
