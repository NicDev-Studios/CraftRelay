# Changelog

Notable changes are documented here. CraftRelay follows semantic versioning with preview rules: patch releases remain compatible within a preview line, while a new `0.x.0` line may contain documented breaking changes.

## [Unreleased]

## [0.1.0] - 2026-07-31

### Added

- Public, Java 21 API for asynchronous messaging, requests, instance discovery, and player lookup.
- Built-in network lifecycle, player presence, location, connection, and broadcast messages.
- Explicit versioned custom-message registration with isolated codecs and request handlers.
- Paper and Velocity plugins backed by Redis Pub/Sub and token-fenced presence leases.
- Duplicate player-session protection across independent Velocity proxies.
- Paper and Velocity example plugins plus a configurable Docker development network.
- Bounded dispatch queues, request capacity, structured diagnostics, and health snapshots.

### Security

- Allowlist-only message decoding without polymorphic class loading.
- Redis lease and session fencing protects newer owners from stale processes.
- Release artifacts include checksums, a CycloneDX SBOM, reviewed license notices, and GitHub attestations.

[Unreleased]: https://github.com/NicDev-Studios/CraftRelay/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/NicDev-Studios/CraftRelay/releases/tag/v0.1.0
