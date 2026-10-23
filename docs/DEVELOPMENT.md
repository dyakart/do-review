# Development

## Environment

Use Java 25 and the Gradle wrapper included in the repository. The default build targets PyCharm 2026.2.3 and pinned language plugins, downloading missing dependencies. To use an installed IDE, pass `-PlocalIdePath=<installation-directory>`.

On Windows, `build.ps1` uses the Java runtime bundled with a local PyCharm 2026.2 installation. It searches the standard JetBrains installation directory or accepts an explicit path:

```powershell
.\build.ps1 -IdePath 'D:/Apps/PyCharm 2026.2'
```

Language adapter dependencies are pinned in [build.gradle](../build.gradle). Local installations can be selected with `tactPluginPath` and `tonPluginPath`.

## Build

```sh
./gradlew check buildPlugin verifyPluginStructure
```

On Windows, use `.\gradlew.bat` or run `.\build.ps1`, which executes these tasks by default. `check` runs the IDE tests, standalone core tests and isolated core compilation.

Plugin archives are written to `build/distributions/`. JUnit reports are in `build/reports/tests/test/`.

## Run and test

Launch a sandbox IDE:

```sh
./gradlew runIde
```

Run a single test class:

```sh
./gradlew test --tests dev.doreview.ide.DoReviewServiceTest
```

The Windows helper forwards Gradle arguments through `-Tasks`, for example `.\build.ps1 -Tasks runIde`.

## Optional CodeGlance integration tests

The regular suite covers the standalone stripe. Tests against the actual CodeGlance plugin require a compatible local installation and a separate, filtered run:

```sh
./gradlew -PcodeGlancePluginPath="/path/to/CodeGlancePro" test --tests dev.doreview.ide.ReviewStripeCodeGlanceTest
```

This property adds CodeGlance and `src/codeGlanceTest/java` to the test runtime. Production has no CodeGlance dependency. The bridge uses internal APIs; changes to it should be checked against the installed plugin's minimap geometry.
