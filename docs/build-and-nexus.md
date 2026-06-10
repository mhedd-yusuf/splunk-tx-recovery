# Build setup — Java 25, Spring Boot 4.0.6, Gradle 9.5, Company Nexus

How the build is wired and how to point it at your corporate Nexus.

## Versions baseline

| Component        | Version       | Where it's pinned                                  |
| ---------------- | ------------- | -------------------------------------------------- |
| Java toolchain   | 25            | `build.gradle` → `java.toolchain.languageVersion`  |
| Spring Boot      | 4.0.6         | `build.gradle` → `org.springframework.boot` plugin |
| Spring DepMgmt   | 1.1.7         | `build.gradle` → `io.spring.dependency-management` |
| Gradle           | 9.2.1         | `gradle/wrapper/gradle-wrapper.properties` (see note below) |
| ShedLock         | 7.7.0         | `build.gradle` → `ext.shedlockVersion`             |
| Resilience4j     | 2.3.0         | `build.gradle` → `ext.resilience4jVersion`         |
| Mockito override | 5.18.0        | `build.gradle` → `dependencyManagement.dependencies` |
| ByteBuddy override | 1.17.6      | `build.gradle` → `dependencyManagement.dependencies` |

The Mockito + ByteBuddy overrides exist because earlier Spring Boot patches
shipped Mockito 5.14 / ByteBuddy 1.15 which can't read JDK 25 bytecode. Spring
Boot 4.0.6's BOM has likely caught up but the explicit pins are kept as
insurance — drop them once `./gradlew dependencies | grep mockito` shows
≥5.18 from the BOM alone.

## Installing Java 25

Gradle 9 auto-detects local JDKs via standard package managers. Install via
one of:

```bash
# macOS — Homebrew
brew install --cask temurin@25

# SDKMAN (any OS)
curl -s "https://get.sdkman.io" | bash
sdk install java 25-tem

# Linux — apt
sudo apt-get install temurin-25-jdk
```

Verify Gradle sees it:

```bash
./gradlew javaToolchains
# Look for "JDK 25 (Temurin)" or similar in the output
```

If you get `Cannot find a Java installation … matching: {languageVersion=25}`,
either the JDK isn't on the standard detection paths or it's a vendor Gradle
doesn't recognise. Override explicitly:

```bash
./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-25 compileJava
```

Or set in `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

> Foojay auto-download is intentionally **not** wired up. Corporate Nexus
> setups frequently block `api.foojay.io`; relying on it makes the build
> brittle. Install Java 25 once; Gradle remembers it forever.

## Company Nexus configuration

Two scopes need a repository: **plugins** (resolved via
`pluginManagement {}`) and **dependencies** (resolved via
`dependencyResolutionManagement {}`). Both are configured centrally in
`settings.gradle`.

### Resolution order (intentional)

1. **`mavenLocal()`** — `~/.m2/repository`. Fast hit for anything you've
   `mvn install`-ed locally.
2. **Company Nexus** — only added when `nexusUrl` is configured (env var
   or Gradle property). If unset, Nexus is skipped silently.
3. **Public mirror** — Maven Central for dependencies; Gradle Plugin
   Portal for plugins.

Gradle queries each repo in order and uses the first that has the artifact.
A missing artifact in your Nexus falls through to Central automatically;
nothing extra is needed.

### How to point at your Nexus

Pick **one** of the following — `settings.gradle` will detect either.

**Option A — User-scope Gradle properties** (recommended; per-developer):

```properties
# ~/.gradle/gradle.properties  (NOT in this repo)
nexusUrl=https://nexus.your-company.example.com/repository/maven-public/
nexusUsername=<your nexus id>
nexusPassword=<your nexus token or password>
```

**Option B — Environment variables** (best for CI):

```bash
export NEXUS_URL=https://nexus.your-company.example.com/repository/maven-public/
export NEXUS_USERNAME=<your nexus id>
export NEXUS_PASSWORD=<your nexus token or password>
```

Env vars win over Gradle properties when both are set.

### Verifying it works

```bash
# 1. Confirm Gradle saw the Nexus URL.
./gradlew --info compileJava 2>&1 | grep -i 'nexus\|repository'

# 2. Force a fresh resolution and watch the URLs Gradle hits.
./gradlew --refresh-dependencies compileJava --info 2>&1 | grep '^Downloading' | head -20
# Lines starting with your Nexus URL = served from Nexus.
# Lines starting with repo.maven.apache.org = fell through to Central.

# 3. Inspect the local cache.
ls ~/.gradle/caches/modules-2/files-2.1/ | head -10
```

### Common Nexus gotchas

- **HTTP, not HTTPS.** Add `allowInsecureProtocol = true` inside the `maven {}`
  block. Don't do this in production CI — only on a private dev network.
- **Self-signed TLS cert.** Either install the cert into the JDK's
  truststore (`keytool -importcert ...`) or set
  `org.gradle.internal.publish.checksums.insecure=true` (last resort, not
  recommended).
- **Mixed snapshot / release repos.** Most companies have separate
  repositories for releases and snapshots. Either put them both behind a
  Nexus "group" (`maven-public`) — the recommended approach — or declare
  two `maven {}` blocks, one per URL.
- **403 on plugin resolution.** Nexus's `maven-public` group usually
  proxies Maven Central but **not** the Gradle Plugin Portal. Either add a
  Gradle Plugin Portal proxy in Nexus or rely on the explicit
  `gradlePluginPortal()` fallback (already in `settings.gradle`).

### Where artifacts end up

| Origin              | Cached at                                              |
| ------------------- | ------------------------------------------------------ |
| Any Gradle resolve  | `~/.gradle/caches/modules-2/files-2.1/<group>/<art>/`  |
| `mvn install` local | `~/.m2/repository/<group>/<artifact>/<version>/`       |
| Gradle wrapper      | `~/.gradle/wrapper/dists/gradle-9.5.0-rc-1-bin/`       |

Both caches are persistent — once an artifact is downloaded, subsequent
builds use the cached copy. No re-download unless you pass
`--refresh-dependencies`.

## What this build expects from Nexus

Required group/artifact coordinates the project pulls:

```
# Spring Boot / framework
org.springframework.boot:*:4.0.6
io.spring.dependency-management:io.spring.dependency-management.gradle.plugin
org.springframework:*

# Java drivers / libs
com.oracle.database.jdbc:ojdbc11:23.6.0.24.10
org.flywaydb:flyway-core
org.flywaydb:flyway-database-oracle

# Cross-cutting libs
io.github.resilience4j:resilience4j-spring-boot3:2.3.0
io.github.resilience4j:resilience4j-reactor:2.3.0
net.javacrumbs.shedlock:shedlock-spring:7.7.0
net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0
io.micrometer:micrometer-registry-prometheus
net.logstash.logback:logstash-logback-encoder:8.0

# Test
org.springframework.boot:spring-boot-starter-test
io.projectreactor:reactor-test
com.squareup.okhttp3:mockwebserver:4.12.0
org.testcontainers:junit-jupiter:1.20.4
org.testcontainers:oracle-free:1.20.4
org.mockito:mockito-core:5.18.0     # override
net.bytebuddy:byte-buddy:1.17.6     # override
```

Confirm these are all proxied by your `maven-public` group. If any are
missing, the build falls back to Maven Central automatically — you may
want to whitelist them in Nexus instead, so your build doesn't depend on
public-network availability.

## Why Gradle 9.2.1 instead of 9.5-rc-1

IntelliJ IDEA's bundled **Android Studio Tooling Plugin** still calls the
removed `org.gradle.util.VersionNumber` class, which Gradle 9.0 deleted.
The plugin is injected into every Gradle sync via an init script at
`/private/var/folders/.../T/sync.studio.tooling.gradle`. On Gradle 9.5-rc-1
this aborts the sync with:

```
java.lang.NoClassDefFoundError: org/gradle/util/VersionNumber
    at com.android.ide.gradle.model.builder.AndroidStudioToolingPluginKt.isGradleAtLeast(...)
```

Gradle 9.2.1 still ships `VersionNumber` (deprecated but present), so the
injected init script runs through. Functionally identical to 9.5-rc-1 for
this project — Spring Boot 4.0.6, Java 25 toolchain, ShedLock 7.7.0,
configuration cache, parallel execution all work the same.

### To switch back to 9.5-rc-1 later

Either of these will work once IntelliJ ships a patched Android plugin:

1. **Disable the Android plugin entirely** — `Settings → Plugins → Installed`
   → uncheck **Android** and **Android Design Tools** → restart. The init
   script is no longer injected at sync time.
2. **Update IntelliJ IDEA** — the JetBrains fix lands once the bundled
   Android plugin stops calling `VersionNumber`.

Then bump the wrapper:

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.0-rc-1-bin.zip
```

Run `./gradlew --stop && ./gradlew test` from the CLI first to confirm it
works. Only then reload the Gradle project in IntelliJ.

## Java 25 runtime + Java 24 bytecode (intentional)

The compile task is pinned to `options.release = 24` in `build.gradle`. We
still run on the JDK 25 toolchain — full Loom + every other JDK 25 runtime
feature is available — but we **emit class file major version 68 (Java 24)**
instead of 69 (Java 25).

Why: Spring Framework 7, shipped with Spring Boot 4.0.6, bundles an older
ASM that doesn't yet know class file version 69. At startup, Spring's
component scan trips on its own main class with:

```
ASM ClassReader failed to parse class file …
Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 69
```

Emitting Java 24 bytecode side-steps this entirely. No application code
needs to change — Java 25's library APIs are unchanged from 24 for our use,
and Loom / virtual threads work at runtime regardless of bytecode version.

Remove the pin once Spring Boot ships ASM 9.9+ (expected in 4.0.7 or 4.1.x):

```groovy
// Remove this block once Spring Boot 4.0.7+ is in your Nexus
tasks.withType(JavaCompile).configureEach {
    options.release = 24
}
```

Verify the bytecode version with:

```bash
javap -v build/classes/java/main/com/example/txrecovery/TxRecoveryApplication.class \
    | grep "major version"
# major version: 68      ← what we want
```

## Spring Boot 4.0.6 migration notes (vs the previous 3.4.x setup)

The codebase itself needed **no source changes**. All 20 unit tests pass
on Spring Boot 4.0.6 with no modification:

```
SplQueryBuilderTest      5/5
SplunkClientTest         3/3
SplunkResultParserTest   3/3
WatermarkServiceTest     5/5
RecoveryServiceTest      4/4
                        ────
                        20/20
```

What changed in the **build**:

- ShedLock bumped 5.16.0 → **7.7.0** (5.x and 6.x lines aren't published;
  ShedLock skipped straight to 7 for Spring Boot 4 support).
- Resilience4j bumped 2.2.0 → **2.3.0**.
- Spring Boot plugin bumped 3.4.3 → **4.0.6**.
- Gradle bumped 8.12 → **9.5.0-rc-1**.
- Toolchain bumped Java 21 → **Java 25**.
- The Foojay JDK auto-resolver was removed (incompatible with Gradle 9.5;
  also bad fit for corporate networks — see above).
