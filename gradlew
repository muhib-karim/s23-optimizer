#!/bin/sh
# Offline launcher: this repository intentionally does not ship an unverified binary JAR.
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
if [ -f "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    if [ -n "$JAVA_HOME" ]; then JAVA_CMD="$JAVA_HOME/bin/java"; else JAVA_CMD=java; fi
    exec "$JAVA_CMD" -classpath "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi
printf '%s\n' 'Gradle wrapper JAR is not bundled. Use installed Gradle 8.10.2. CI provisions it with setup-gradle.' >&2
exit 1
