# Build Environment

## Recorded environment

- OS: Ubuntu 24.04.4 LTS container
- Java: Microsoft OpenJDK `25.0.2`
- Gradle runtime: `9.3.1`
- Gradle wrapper: `gradle-9.3.1-bin.zip`
- AGP: `9.1.1`
- Kotlin plugin: `2.2.10`
- KSP: `2.3.5`
- compileSdk: `36` (minor API `1`)
- minSdk: `24`
- targetSdk: `36`

The Gradle build reports embedded Kotlin `2.2.21`; the project Kotlin plugin remains `2.2.10`.

## Required Android SDK packages

Install Android SDK Platform 36 and the matching Android SDK Build-Tools/platform tools required by Android Studio. The exact installed build-tools revision is resolved by the Android Gradle Plugin. An Android SDK location must be supplied through `local.properties` (`sdk.dir`) or the standard environment configuration.

Release builds additionally require an explicitly configured keystore through `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`; debug builds do not require release signing credentials.

## Commands

```bash
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew :app:compileDebugUnitTestKotlin
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew test
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew lint
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew assembleDebug
```

Do not use `clean` as part of normal handoff validation unless explicitly authorized; existing build artifacts are useful for diagnosing KSP/toolchain issues.
