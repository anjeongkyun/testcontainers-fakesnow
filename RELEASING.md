# Releasing

Publishing goes to Maven Central through the [Central Portal](https://central.sonatype.com), using the [vanniktech publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/).

## One-time setup

### 1. Central Portal account and namespace

Sign in at [central.sonatype.com](https://central.sonatype.com) **with GitHub**. Signing in that way auto-verifies the `io.github.<username>` namespace, so no verification repository is needed.

Confirm under the account menu → *View Namespaces* that `io.github.anjeongkyun` is listed and verified.

### 2. User token

Account menu → *Generate User Token*. This yields a username/password pair that is not your login. It's what the build uses.

### 3. GPG key

Central requires signed artifacts.

```shell
gpg --gen-key                                              # note the key id it prints
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>  # publish so Central can verify
gpg --armor --export-secret-keys <KEY_ID>                  # the private key block, for the next step
```

### 4. Credentials

Put these in `~/.gradle/gradle.properties` (never in this repository):

```properties
mavenCentralUsername=<user token username>
mavenCentralPassword=<user token password>
signingInMemoryKeyPassword=<key passphrase>
```

The signing key itself does not go here. It is an ASCII-armored block spanning many lines, which a Java properties file can't hold, and stripping the newlines produces `Could not read PGP secret key`. Pass it as an environment variable at release time instead:

```shell
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
```

Signing is skipped when no key is configured, so ordinary builds work without one.

## Release

1. Set the version in `build.gradle.kts` (no `-SNAPSHOT`).
2. Verify locally:

```shell
./gradlew clean build
./gradlew publishToMavenLocal
ls ~/.m2/repository/io/github/anjeongkyun/testcontainers-fakesnow/<version>/
```

   Expect the jar, `-sources.jar`, `-javadoc.jar` and `.pom`. Central rejects a release missing any of them.

3. Publish, with the signing key in the environment:

```shell
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"

./gradlew publishToMavenCentral            # uploads, then release manually in the portal
# or
./gradlew publishAndReleaseToMavenCentral  # uploads and releases in one step
```

Confirm signing works first: `publishToMavenLocal` should produce a `.asc` next to every artifact.

4. Tag and push:

```shell
git tag v<version> && git push origin v<version>
```

Releases are immutable. A published version can't be changed or removed, only superseded.

## After the first release

Add the module to the [Testcontainers community module registry](https://github.com/testcontainers/community-module-registry) by opening a PR that adds `modules/fakesnow/index.md` with `maintainer: community` pointing at this repository. The entry's `installation` block needs the published Maven coordinates, which is why it has to wait for a release.
