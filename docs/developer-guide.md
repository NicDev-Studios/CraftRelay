# CraftRelay Developer Guide

CraftRelay targets Java 21. The public integration surface is
`craftrelay-api`; platform, Redis, Common, and relocated runtime classes are
implementation details.

## Gradle dependency

Use the API module only:

```kotlin
dependencies {
    compileOnly("de.nicdevtv:craftrelay-api:0.1.0-SNAPSHOT")
}
```

Until snapshots are published, a composite build or a local Maven publication
can provide the same coordinate.

## Paper

Declare `depend: [CraftRelay]` when the API is required. Resolve the service
when your plugin enables and observe Bukkit service events if your plugin may
enable before CraftRelay's asynchronous Redis startup finishes:

```java
RegisteredServiceProvider<CraftRelayApi> registration =
        getServer().getServicesManager().getRegistration(CraftRelayApi.class);
CraftRelayApi api = registration == null ? null : registration.getProvider();
```

CraftRelay unregisters the service before shutdown. Drop stored API references
and close every `Subscription` when the matching service is unregistered.

## Velocity

Declare a required `craftrelay` plugin dependency. Resolve its plugin instance
as `CraftRelayProvider` and call `api()`. The result is empty during startup and
shutdown. `CraftRelayReadyEvent` announces a later successful startup without
introducing a singleton.

```java
Optional<CraftRelayApi> api = server.getPluginManager()
        .getPlugin("craftrelay")
        .flatMap(container -> container.getInstance())
        .filter(CraftRelayProvider.class::isInstance)
        .map(CraftRelayProvider.class::cast)
        .flatMap(CraftRelayProvider::api);
```

## Threading and lifecycle

`publish`, `request`, `instances`, and `player` are asynchronous. Do not call
`get()` or `join()` on Paper/Velocity threads. Attach a completion stage, then
schedule Bukkit, Velocity, player, sender, or audience access back onto the
platform scheduler.

Message listeners run on isolated CraftRelay dispatchers. They must still hand
platform work to the platform scheduler. Close subscriptions idempotently on
API replacement and plugin shutdown.

Redis Pub/Sub remains best effort. A successful publish means Redis accepted
the payload, not that every target processed it.

## Example commands

Both example plugins register `/craftrelayexample` and `/crelay` with
`craftrelay.example.admin`:

| Command | Demonstrates |
| --- | --- |
| `state` | Local API lifecycle |
| `instances` | Immutable Redis-backed instance snapshot |
| `player <uuid>` | Authoritative player-presence lookup |
| `broadcast <message>` | `GlobalBroadcastMessage` to all proxies |
| `connect <uuid> <server-id>` | `PlayerConnectRequest` to all proxies |

Only the Velocity example displays incoming broadcasts. This prevents duplicate
chat output when the Paper example is installed as well. Both adapters render
their output with MiniMessage. Dynamic API and command values are inserted as
unparsed placeholders, so player names, server IDs, and broadcast content
cannot inject MiniMessage tags.

## Plugin author metadata

This setting is relevant only to CraftRelay maintainers. It fills the author
list in the Paper and Velocity plugin descriptors; it does not affect the API,
network protocol, or runtime behavior. Local metadata builds can override it:

```shell
./gradlew build -PcraftrelayAuthors=NicDevTV,ContributorTwo
```

Names are trimmed, empty entries are rejected, duplicates are removed in input
order, and the list is limited to 10. Local and normal CI builds default to
`NicDev-Studios`. Tag release builds obtain human contributor logins from
GitHub automatically and fail if no valid list can be produced. Plugins using
CraftRelay do not need to set this property.

## Creating a release

The release workflow is triggered by a semantic version tag. Prepare and push
the release commit, then create and push the tag:

```shell
git tag -a v0.1.0 -m "CraftRelay v0.1.0"
git push origin v0.1.0
```

GitHub Actions validates the tag, builds version `0.1.0`, runs unit and Redis
integration tests, resolves the top 10 human contributors, and creates a draft
GitHub release containing the four installable Paper/Velocity JARs. Review its
generated notes and artifacts in GitHub, then publish the draft manually.

After creating the draft GitHub release, the workflow signs and publishes
`de.nicdevtv:craftrelay-api` through the Maven Central Portal. Maven Central
releases are immutable; a correction requires a new patch version.
