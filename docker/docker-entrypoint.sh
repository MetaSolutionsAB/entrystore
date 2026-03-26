#!/bin/sh

set -e

CONFIG_FILE="/srv/entrystore/entrystore.properties"

if [ -n "$ENTRYSTORE_CONFIG_URI" ]; then
  case "$ENTRYSTORE_CONFIG_URI" in
    https://*)
      echo "Downloading configuration from $ENTRYSTORE_CONFIG_URI"
      wget -nv -O "$CONFIG_FILE" --timeout=10 --tries=3 "$ENTRYSTORE_CONFIG_URI"
      if [ ! -s "$CONFIG_FILE" ]; then
        echo "Error: Downloaded configuration is empty" >&2
        exit 1
      fi
      ;;
    file://*)
      CONFIG_PATH="${ENTRYSTORE_CONFIG_URI#file://}"
      echo "Loading configuration from $CONFIG_PATH"
      cp "$CONFIG_PATH" "$CONFIG_FILE"
      ;;
    http://*)
      echo "Error: HTTP is not supported for configuration download due to security risks." >&2
      echo "Use https:// or mount a file and use file:///path/to/entrystore.properties" >&2
      exit 1
      ;;
    *)
      echo "Error: Unsupported URI scheme in ENTRYSTORE_CONFIG_URI: $ENTRYSTORE_CONFIG_URI" >&2
      echo "Supported schemes: https://, file://" >&2
      exit 1
      ;;
  esac
  echo "Configuration loaded successfully"
fi

if [ -z "$ENTRYSTORE_CONFIG_URI" ] && [ ! -f "$CONFIG_FILE" ]; then
  echo "Warning: No configuration found. Set ENTRYSTORE_CONFIG_URI or mount a file at $CONFIG_FILE." >&2
  echo "EntryStore will start with default settings, which may not be suitable for production." >&2
fi

exec java -jar /srv/entrystore/entrystore.jar \
  --spring.config.import=optional:file://"$CONFIG_FILE" \
  "$@"
