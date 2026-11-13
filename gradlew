#!/bin/sh
# ==============================================================================
# Gradle Startup Script for UN*X — NexusTS (Gradle 8.13)
# ==============================================================================

# Resolve links: $0 may be a link
app_path=$0
while [ -h "$app_path" ]; do
    ls=$( ls -ld "$app_path" )
    link=$( expr "$ls" : '.*-> \(.*\)$' )
    if expr "$link" : '/.*' > /dev/null; then
        app_path=$link
    else
        app_path=$( dirname "$app_path" )"/$link"
    fi
done

APP_HOME=$( cd "${app_path%"${app_path##*/}"}" > /dev/null; pwd -P ) || exit
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Auto-bootstrap gradle-wrapper.jar (v8.13.0) if not yet present in working tree
if [ ! -f "$CLASSPATH" ]; then
    if command -v gradle >/dev/null 2>&1; then
        exec gradle -p "$APP_HOME" "$@"
    fi
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
    mkdir -p "$APP_HOME/gradle/wrapper"
    if command -v curl >/dev/null 2>&1; then
        echo "==> Bootstrapping gradle-wrapper.jar (v8.13.0)..." >&2
        curl -fsSL -o "$CLASSPATH" "$WRAPPER_URL"
    elif command -v wget >/dev/null 2>&1; then
        echo "==> Bootstrapping gradle-wrapper.jar (v8.13.0)..." >&2
        wget -q -O "$CLASSPATH" "$WRAPPER_URL"
    else
        echo "ERROR: gradle-wrapper.jar missing and neither gradle, curl, nor wget is available." >&2
        exit 1
    fi
fi

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    fi
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=gradlew" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
