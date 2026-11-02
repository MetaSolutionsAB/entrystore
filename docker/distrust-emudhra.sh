#!/bin/sh
# Removes the root CAs distrusted by https://openjdk.org/emudhra (CVE-2026-114820) from the JDK truststore
# and the Alpine system store, and fails the build if one is left. Matches by subject, not by alias:
# Temurin's cacerts aliases differ from upstream OpenJDK's, and Alpine's file names from both.
# keytool writes to a file, never into a pipe, so that set -e catches a keytool failure.
set -eu

PATTERN='CN=(emSign Root CA - G[12]|emSign ECC Root CA - G3|AAA Certificate Services)(,|$)'
CACERTS="$JAVA_HOME/lib/security/cacerts"
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

keytool_en() {
	keytool -J-Duser.language=en "$@" > "$OUT"
}

# Prints "yes" on a match; assign its output (set -e applies) rather than test it in an if (it does not).
owner_matches() {
	awk -v pattern="$PATTERN" '/^Owner: / && substr($0, 8) ~ pattern { print "yes"; exit }' "$OUT"
}

# Called in $(...), where ash ignores set -e.
distrusted_aliases() {
	keytool_en -list -v -keystore "$CACERTS" -storepass changeit || return 1
	awk -v pattern="$PATTERN" '
		/^Alias name: / { alias = substr($0, 13) }
		/^Owner: / && substr($0, 8) ~ pattern { print alias }' "$OUT"
}

aliases=$(distrusted_aliases)
printf '%s\n' "$aliases" | while IFS= read -r alias; do
	[ -n "$alias" ] || continue
	echo "Removing \"$alias\" from $CACERTS"
	keytool -delete -keystore "$CACERTS" -storepass changeit -alias "$alias"
done

# Alpine: deselect in ca-certificates.conf, so update-ca-certificates also drops the /etc/ssl/certs links.
for f in /usr/share/ca-certificates/mozilla/*.crt; do
	keytool_en -printcert -file "$f"
	matched=$(owner_matches)
	if [ -n "$matched" ]; then
		entry="${f#/usr/share/ca-certificates/}"
		echo "Deselecting $entry in /etc/ca-certificates.conf"
		sed -i "s|^$entry\$|!$entry|" /etc/ca-certificates.conf
	fi
done
update-ca-certificates

aliases=$(distrusted_aliases)
keytool_en -printcert -file /etc/ssl/certs/ca-certificates.crt
matched=$(owner_matches)
if [ -n "$aliases" ] || [ -n "$matched" ]; then
	echo "Error: a distrusted eMudhra root CA is still trusted" >&2
	exit 1
fi
