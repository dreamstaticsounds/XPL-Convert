#!/bin/sh
# Run the GUI (no arguments) or the CLI (pass flags).  Builds the jar first if missing.
set -e
cd "$(dirname "$0")"

[ -f XPLConvert.jar ] || ./build.sh

find_java() {
    for c in "$JAVA_HOME/bin/java" \
             "$(command -v java 2>/dev/null || true)" \
             "$HOME"/opt/jdk-*/bin/java \
             /usr/lib/jvm/*/bin/java \
             /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
        if [ -n "$c" ] && [ -x "$c" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

JAVA=$(find_java) || {
    echo "no Java runtime found." >&2
    echo "Install Java 17 or newer, e.g. Temurin: https://adoptium.net" >&2
    exit 1
}
exec "$JAVA" -jar XPLConvert.jar "$@"
