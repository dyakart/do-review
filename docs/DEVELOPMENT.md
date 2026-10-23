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

Plugin archives are written to `build/distributions/`. JUnit reports are in `build/reports/tests/test/`. Tests default to `build/test-data/review-history/` for local SQLite data through `doreview.data.path`; individual tests may override this with a disposable directory. Test runs must not write history into the user's normal application-data directory.

## Runtime dependencies

The plugin packages Xerial SQLite JDBC 3.53.4.0 and Gson 2.13.2 through regular Gradle dependencies. SQLite uses the driver's native libraries, so installing or updating the plugin requires an IDE restart. The plugin does not depend on the IDE's internal SQLite API or Database Tools plugin.

[Third-party notices](THIRD_PARTY_LICENSES.md) are copied to `META-INF/THIRD_PARTY_LICENSES.md` in the plugin JAR. When changing dependencies, preserve their upstream license files and verify the distribution contains the required libraries and notices.

## Run and test

Launch a sandbox IDE:

```sh
./gradlew runIde
```

Run a single test class:

```sh
./gradlew test --tests dev.doreview.ide.SharedReviewHistoryStoreTest
```

`SharedReviewHistoryStoreTest` exercises the production facade with native SQLite, Git branch changes, XML import and editor lifecycle. `SharedHistoryRepositoryTest` covers portable event merging, durable pending work and concurrent database access. Other IDE tests use detached legacy fixtures where appropriate.

The Windows helper forwards Gradle arguments through `-Tasks`, for example `.\build.ps1 -Tasks runIde`.

## Performance fixtures

Run the synthetic archive and active-file measurements:

```sh
./gradlew test --tests dev.doreview.core.ReviewHistoryPerformanceTest --tests 'dev.doreview.ide.SharedHistoryRepositoryTest.testLargeArchive*'
```

The archive contains 5,000 events across 500 files sharing one four-byte snapshot. The active file has 80 snapshots, 400 reviews and 25,600 proof rows over 320 source rows; the current text changes one row and appends an unreviewed row. It supplies uniform context, excluding PSI, disk I/O and editor painting.

A Windows/Java 25 sample measured:

| Operation | Cold | Warm |
| --- | ---: | ---: |
| Archive index scan | 4,094.2 ms | 520.8 ms |
| Active-file projection | 115.2 ms | 36.1 ms |
| Active-file checkpoint | 116.0 ms | 67.5 ms |

Appending one archive event took 858.8 ms; constructing the active-file fixture took 161.0 ms. Warm active-file values are medians of three operations with new workspaces. Assertions check output, cache reuse and bounds; timings are observations rather than performance guarantees.

Sampled used-heap deltas were +21.9 MiB for the archive, −81.5 MiB constructing the active-file fixture, +34.0 MiB for its cold pass and +63.0 MiB across three warm passes. These samples include garbage collection and unreclaimed allocations; they measure neither retained memory nor peak memory.

## Optional CodeGlance integration tests

The regular suite covers the standalone stripe. Tests against the actual CodeGlance plugin require a compatible local installation and a separate, filtered run:

```sh
./gradlew -PcodeGlancePluginPath="/path/to/CodeGlancePro" test --tests dev.doreview.ide.ReviewStripeCodeGlanceTest
```

This property adds CodeGlance and `src/codeGlanceTest/java` to the test runtime. Production has no CodeGlance dependency. The bridge uses internal APIs; changes to it should be checked against the installed plugin's minimap geometry.
