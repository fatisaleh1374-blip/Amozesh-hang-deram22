# Migration Checklist

After cloning the repository into a new account:

1. Install a supported JDK and verify `java -version`.
2. Install Android SDK Platform 36 and required platform/build tools.
3. Review `local.properties`; set only the local Android SDK path and required local secrets.
4. Verify the Gradle wrapper and run Gradle sync in Android Studio.
5. Run `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew :app:compileDebugUnitTestKotlin`.
6. Run `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew test`.
7. Run `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew lint`.
8. Run `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew assembleDebug`.
9. Confirm `git status --short --branch` and inspect the diff before making new changes.
10. Read `AI_CONTEXT.md`, `PROJECT_HANDOFF.md`, and `ARCHITECTURE_DECISIONS.md` before implementation work.

Never copy `local.properties`, passwords, or signing material into a public transfer. Configure release signing only through the documented environment variables.
