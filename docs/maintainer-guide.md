# CraftRelay Maintainer Guide

This guide covers repository and release operations for CraftRelay maintainers.
Plugin developers integrating the public API should use the
[Developer Guide](developer-guide.md).

## Plugin author metadata

All installable Paper and Velocity artifacts use the same Gradle property:

```shell
./gradlew build -PcraftrelayAuthors=NicDevTV,ContributorTwo
```

Names are trimmed, empty entries are rejected, duplicates are removed in input
order, and the list is limited to 10. Local and normal CI builds default to
`NicDev-Studios`. Tag builds obtain the top 10 human contributor logins from
GitHub and fail when no valid list can be produced.

The property affects plugin descriptors only. It does not change Maven POM
developers, the public API, network protocol, or runtime behavior.

## Creating a release

The release workflow is triggered by a semantic version tag. Prepare and push
the reviewed release commit, then create and push the tag:

```shell
git tag -a v0.1.0 -m "CraftRelay v0.1.0"
git push origin v0.1.0
```

GitHub Actions validates the tag, builds the non-SNAPSHOT version, runs unit and
Redis integration tests, resolves contributor metadata, and creates a draft
GitHub release containing the four installable Paper/Velocity JARs. Review its
generated notes and artifacts before publishing the draft.

The same workflow signs and publishes `de.nicdevtv:craftrelay-api` through the
Maven Central Portal. It requires these repository secrets:

* `MAVEN_CENTRAL_USERNAME`
* `MAVEN_CENTRAL_PASSWORD`
* `SIGNING_KEY`
* `SIGNING_PASSWORD`

The `de.nicdevtv` namespace must already be verified in Maven Central. Published
Maven Central releases are immutable; corrections require a new patch version.
