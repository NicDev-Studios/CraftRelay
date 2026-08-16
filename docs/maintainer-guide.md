# CraftRelay Maintainer Guide

This document is for repository maintainers. Plugin authors should use the [Developer Guide](developer-guide.md).

## Version contract

Development remains on `0.1.0-SNAPSHOT` in `gradle.properties`. A release version is supplied only for a release build:

```shell
./gradlew releaseCheck releaseBundle \
  -PcraftrelayVersion=0.1.0 \
  -PcraftrelayAuthors=NicDevTV
```

Before `1.0`, `0.x.0` opens a preview line that may contain documented breaking changes. Patch releases in that line are checked against `0.x.0` and must remain source- and binary-compatible. From `1.0`, compatibility is enforced within each major line.

The public Maven artifact is `de.nicdevtv:craftrelay-api`. Platform and example plugins are distributed as GitHub Release assets, not Maven libraries.

## Author metadata

All four installable plugins read the same comma-separated `craftrelayAuthors` Gradle property. Values are trimmed, deduplicated in input order, reject empty entries, and are limited to ten names. Ordinary builds use `NicDev-Studios`.

Release preflight resolves the ten highest-ranked human GitHub contributors once and uses that exact list for all plugin artifacts. Bots are excluded. Author metadata has no effect on the Maven POM, API, or runtime behavior.

## Prepare a release

1. Confirm that the intended version has a curated section in `CHANGELOG.md`.
2. Run the local release checks with a numeric, non-SNAPSHOT version.
3. Review the four JARs, SBOM, notices, checksums, and extracted release notes under `build/release/<version>/`.
4. Commit the reviewed release state to `main`.
5. Create an annotated tag on that commit.

```shell
git tag -a v0.1.0 -m "CraftRelay v0.1.0"
git push origin v0.1.0
```

## Stage 1: release preflight

The tag workflow is reversible. It:

- verifies the strict `vMAJOR.MINOR.PATCH` tag and its ancestry in `main`;
- validates the changelog and contributor metadata;
- runs unit tests, Redis integration tests, API compatibility, release checks, and `devSmoke`;
- builds the release bundle twice and compares every file hash;
- creates provenance and SBOM attestations;
- creates or updates a GitHub draft tied to the exact tag commit.

No Maven Central credential is available to this workflow. A failed preflight cannot publish anything irreversible. Version `0.x` drafts are marked as pre-releases.

Review the draft's release notes, exact asset list, checksums, SBOM, and workflow result before continuing. Do not replace assets manually.

## Stage 2: finalize

Run **Finalize release** from GitHub Actions with the tag and the exact confirmation `publish`. The job uses the protected `maven-central` environment and requires:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

Finalization downloads the existing draft assets, verifies the exact allowlist, both checksum files, tag/commit identity, and GitHub attestations. It then builds and validates the signed API publication locally.

If `de.nicdevtv:craftrelay-api:<version>` is absent, the workflow publishes and releases it through the Maven Central Portal. The GitHub draft becomes public only after the Central artifact is confirmed. Published Maven coordinates and release assets are immutable; fixes require a new patch release.

Repository settings should enable immutable GitHub releases. Protect the `maven-central` environment with required reviewer approval.

## Recovery

- **Preflight failed:** fix the repository, move the tag only while no release or Central artifact exists, and rerun.
- **Draft exists, Central was not called:** rerun preflight only if the tag still points to the same commit, then review again.
- **Central succeeded, GitHub publication failed:** rerun Finalize. It detects the existing Central coordinate and only completes the GitHub publication.
- **Central validation failed:** leave the draft unpublished, inspect the Portal deployment and workflow logs, then fix the release process. Never reuse a version already published to Central.
- **Central is published but not yet searchable:** wait for repository synchronization before retrying. Search indexing can lag behind direct repository availability.

## Dependency and supply-chain maintenance

`gradle/libs.versions.toml` is the only source for library and plugin versions. GitHub Actions are pinned to complete commit SHAs with readable version comments; Dependabot updates both Gradle dependencies and Action pins.

`gradle/verification-metadata.xml` pins dependency checksums. A changed Paper or Velocity snapshot must be reviewed and deliberately recorded; do not accept checksum changes blindly.

`gradle/runtime-licenses.properties` is an allowlist for dependencies embedded in platform JARs. Review a component's actual license before adding it. `verifyRuntimeLicenses` fails for unknown runtime coordinates.
