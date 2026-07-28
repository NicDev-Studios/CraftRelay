# CraftRelay

A lightweight, modular, and high-performance network synchronization framework for Minecraft proxies and servers.

## Features

* 🚀 Multi-proxy & multi-server support
* 📡 Redis-based messaging
* 🔌 Velocity & Paper support
* ⚡ Async, thread-safe API
* 🧩 Easy integration for plugins

## Development build

CraftRelay currently targets Java 21, Paper 1.20.6, Velocity 3.4, and Redis.

```shell
./gradlew clean build
```

The deployable platform plugins are created at:

* `craftrelay-platform-paper/build/libs/craftrelay-platform-paper-0.1.0-SNAPSHOT.jar`
* `craftrelay-platform-velocity/build/libs/craftrelay-platform-velocity-0.1.0-SNAPSHOT.jar`
* `craftrelay-example-plugin/paper/build/libs/craftrelay-example-paper-0.1.0-SNAPSHOT.jar`
* `craftrelay-example-plugin/velocity/build/libs/craftrelay-example-velocity-0.1.0-SNAPSHOT.jar`

On first startup, each plugin creates `config.yml` and stops until
`instance.id` is changed from `change-me`. Every node needs a unique, stable
ID. All nodes in one network must use the same Redis connection and
messaging/presence prefix.

Paper exposes `CraftRelayApi` through the Bukkit services manager. Velocity
plugins resolve the platform-neutral `CraftRelayProvider` from their declared
CraftRelay dependency and can additionally listen for `CraftRelayReadyEvent`.

## Developer example

Install the matching CraftRelay and CraftRelay Example JAR on Paper and
Velocity. Both examples expose `/craftrelayexample` with alias `/crelay` and
permission `craftrelay.example.admin`:

* `state`
* `instances`
* `player <uuid>`
* `broadcast <message>`
* `connect <uuid> <server-id>`

The commands never wait on API futures. Platform output is scheduled back onto
the Paper or Velocity scheduler. See the
[Developer Guide](docs/developer-guide.md) for dependency setup, lifecycle
access, custom messages, request handlers, and subscription cleanup. Repository
and release operations are documented separately in the
[Maintainer Guide](docs/maintainer-guide.md).

The local Docker topology and its reproducible smoke test are documented in
[`docker/README.md`](docker/README.md).

The developer network is cross-platform:

```shell
./gradlew devUp
./gradlew devSmoke
./gradlew devDown
```

Copy `docker/.env.example` to `docker/.env` to override the Paper/Velocity
counts, Minecraft or Velocity version, memory, first proxy port, or `PAPER_OPS`.
The topology defaults to two Paper servers, two Velocity proxies, and Minecraft
`1.20.6`; configured operators are synchronized to every generated server.

## License

Licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

---

Made with ❤️ by NicDev-Studios
