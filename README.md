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

On first startup, each plugin creates `config.yml` and stops until
`instance.id` is changed from `change-me`. Every node needs a unique, stable
ID. All nodes in one network must use the same Redis connection and
messaging/presence prefix.

Paper exposes `CraftRelayApi` through the Bukkit services manager. Velocity
plugins can use `CraftRelayVelocityPlugin.api()` after receiving
`CraftRelayReadyEvent`.

## License

Licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

---

Made with ❤️ by NicDev-Studios
