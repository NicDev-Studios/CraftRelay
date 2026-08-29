<div align="center">

# CraftRelay

<pre align="center" style="font-family: Consolas, 'DejaVu Sans Mono', monospace; line-height: 1; overflow-x: auto;"><code>
   ____ ____      _    _____ _____ ____  _____ _        _ __   __
  / ___|  _ \    / \  |  ___|_   _|  _ \| ____| |      / \\ \ / /
 | |   | |_) |  / _ \ | |_    | | | |_) |  _| | |     / _ \\ V /
 | |___|  _ <  / ___ \|  _|   | | |  _ <| |___| |___ / ___ \| |
  \____|_| \_\/_/   \_\_|     |_| |_| \_\_____|_____/_/   \_\_|
</code></pre>

Reliable messaging and presence for Paper and Velocity networks.

[![Build](https://github.com/NicDev-Studios/CraftRelay/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/NicDev-Studios/CraftRelay/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NicDev-Studios/CraftRelay?include_prereleases&label=release)](https://github.com/NicDev-Studios/CraftRelay/releases)
[![API on Maven Central](https://img.shields.io/maven-central/v/de.nicdevtv/craftrelay-api?label=API%20on%20Maven%20Central)](https://central.sonatype.com/artifact/de.nicdevtv/craftrelay-api)
[![Embedded on Maven Central](https://img.shields.io/maven-central/v/de.nicdevtv/craftrelay-embedded?label=Embedded%20on%20Maven%20Central)](https://central.sonatype.com/artifact/de.nicdevtv/craftrelay-embedded)
[![Status](https://img.shields.io/badge/status-developer%20preview-orange)](#status)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License](https://img.shields.io/github/license/NicDev-Studios/CraftRelay)](LICENSE)
[![Lines of Code](https://img.shields.io/endpoint?url=https%3A%2F%2Ftokei.kojix2.net%2Fbadge%2Fgithub%2FNicDev-Studios%2FCraftRelay%2Flines)](https://tokei.kojix2.net/github/NicDev-Studios/CraftRelay)

</div>

CraftRelay connects Paper servers and Velocity proxies through Redis. It provides one asynchronous Java API for targeted messages, request/response calls, instance discovery, and player presence without exposing Redis or platform internals to other plugins.

> [!IMPORTANT]
> CraftRelay is preparing its first `v0.1.0` developer preview. The API is usable, but releases before `1.0` may introduce documented breaking changes between minor versions.

## Status

The repository is preparing the first `v0.1.0` developer preview. The build,
Redis integration tests, release checks, and local Docker smoke tests run in
CI. The first published release will be a pre-release; patch releases in the
same `0.1.x` line keep the documented API contract.

## What it provides

- Targeted messaging to the whole network, proxies, servers, groups, or one instance
- Typed request/response calls with correlation, timeouts, and bounded capacity
- Redis-backed instance and player presence with leases, fencing, and crash expiry
- Duplicate-session protection across multiple Velocity proxies
- Explicit, versioned custom messages without dynamic class deserialization
- Non-blocking Paper and Velocity integrations with isolated listener execution

Redis Pub/Sub is deliberately best effort. CraftRelay does not provide an offline queue or durable message delivery.

## Install

Download the matching Paper and Velocity JARs from the [latest GitHub release](https://github.com/NicDev-Studios/CraftRelay/releases), place them in each platform's `plugins` directory, and start each node once. CraftRelay creates a strict `config.yml` and remains disabled until `instance.id` is changed from `change-me`.

Every node needs a unique, stable instance ID. Nodes in the same network must share the Redis connection and CraftRelay prefix.

Plugins using the installed platform adapters depend only on the platform-neutral API:

```kotlin
dependencies {
    compileOnly("de.nicdevtv:craftrelay-api:0.1.0")
}
```

Plugins that want to own the complete lifecycle can embed CraftRelay instead:

```kotlin
dependencies {
    implementation("de.nicdevtv:craftrelay-embedded:0.1.0")
}
```

Bundle the SDK with your plugin's Shadow JAR and give every embedded host its
own stable instance ID and configuration directory. Embedded nodes provide
messaging, requests, instance presence, and read-only player lookups; they do
not register platform services or automatically manage player sessions. Hosts
may pass `startupLogger(...)` to print the banner and one short startup line;
all ready, failure, and runtime logging remains host-owned.

Paper exposes `CraftRelayApi` through Bukkit's `ServicesManager`. Velocity exposes `CraftRelayProvider` through the declared CraftRelay plugin dependency and fires `CraftRelayReadyEvent` after asynchronous startup.

See the [Developer Guide](docs/developer-guide.md) for lifecycle-safe access, threading rules, custom messages, and request handlers.

## Build and test

CraftRelay requires Java 21. Unit tests and the normal build do not require Docker:

```shell
./gradlew clean build
```

Redis integration tests and the complete local network use Docker:

```shell
./gradlew integrationTest
./gradlew devSmoke
./gradlew embeddedSmoke
```

`devUp` starts an editable network with Redis, two Paper servers, two Velocity proxies, and both example plugins. Copy `docker/.env.example` to `docker/.env` to change the topology or Minecraft version.

```shell
./gradlew devUp
./gradlew devLogs
./gradlew devDown
```

The Docker workflow is documented in [docker/README.md](docker/README.md).

## Security

Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md). Do not include Redis credentials, player data, or production payloads in public issues.

## License

CraftRelay is licensed under the [Apache License 2.0](LICENSE).
