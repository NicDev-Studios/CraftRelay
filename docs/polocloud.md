# PoloCloud configuration contract

CraftRelay reads per-process identity overrides from environment variables.
These are CraftRelay's variable names; configure your PoloCloud service or
template to set them. This document does not assume PoloCloud provides a
particular built-in variable for an instance name.

## Variables

| Variable | Required | Behavior |
| --- | --- | --- |
| `CRAFTRELAY_INSTANCE_ID` | Yes for dynamic services | Overrides `instance.id`. Must be non-empty, must not be `change-me`, and must be unique among every live CraftRelay node using the same Redis database/keyspace and prefix. |
| `CRAFTRELAY_INSTANCE_GROUP` | No | Overrides `instance.group`. If unset, YAML is used; if set to an empty value, the group is cleared. |
| `CRAFTRELAY_REDIS_USERNAME` | No | Overrides `redis.username`. If unset, YAML is used; if set to an empty value, the username is cleared. |
| `CRAFTRELAY_REDIS_PASSWORD` | Only when Redis uses a password | Overrides `redis.password`. Supply it from the orchestrator's secret facility. Empty values are rejected. |

The environment is read independently by each Paper or Velocity process. The
orchestrator must provide a different `CRAFTRELAY_INSTANCE_ID` to each live
service. Within the same Redis database/keyspace and prefix, lease claims reject
an ID that is already active, including an ID duplicated across a proxy and a
backend. After a process disappears, its lease expires using the configured TTL.

## Example service values

Map these CraftRelay variables in each PoloCloud service's environment or
secret settings. The service names and values below are examples of the
operator's contract, not PoloCloud-native variable names.

| Service | `CRAFTRELAY_INSTANCE_ID` | `CRAFTRELAY_INSTANCE_GROUP` |
| --- | --- | --- |
| lobby backend 1 | `paper-lobby-1` | `lobby` |
| lobby backend 2 | `paper-lobby-2` | `lobby` |
| proxy 1 | `proxy-eu-1` | `eu` |
| proxy 2 | `proxy-eu-2` | `eu` |

For dynamically numbered services, have the orchestrator assign an ID that is
unique across all concurrently running proxies and backends. Keep all nodes on
the same CraftRelay Redis prefix, Redis database, and shared Redis service so
the lease claims see the same keyspace.

## Static YAML and secrets

Keep the existing `config.yml` format for shared, non-secret settings. It
remains supported. For example:

The YAML shown below contains only the relevant sections. Keep the existing
`messaging`, `requests`, `presence`, and `platform` sections in the complete
configuration file.

```yaml
config-version: 1

instance:
  id: "change-me" # overridden by CRAFTRELAY_INSTANCE_ID for each service
  group: "default" # overridden by CRAFTRELAY_INSTANCE_GROUP when set

redis:
  host: "redis.internal.example" # set this to your shared Redis endpoint
  port: 6379
  username: null
  password: null
  database: 0
  tls: true
  connection-timeout: "5s"
```

The example contains no Redis credential. Set `CRAFTRELAY_REDIS_USERNAME` and
`CRAFTRELAY_REDIS_PASSWORD` through PoloCloud's secret store and inject them
into every CraftRelay service that needs Redis authentication. If you do not
use Redis authentication, leave the variables unset. Do not put credentials
in image build arguments, repository files, or service logs.

An unset identity variable falls back to YAML. Empty IDs, missing IDs,
surrounding whitespace, and the `change-me` placeholder are rejected during
configuration loading. The same static YAML works without environment
overrides as long as it contains a valid ID. Redis usernames and passwords are
redacted in configuration representations; diagnostic events omit exception
messages so third-party Redis errors cannot print connection details.

## Redis and outage behavior

Configure one Redis service for the entire CraftRelay network. CraftRelay uses
Redis Pub/Sub for best-effort messages and Redis keys with expiring leases for
presence and session ownership. Pub/Sub is not durable delivery: a message
published while a node is disconnected is not stored for later replay, and
CraftRelay is not a persistent queue or general-purpose database. MariaDB
cannot replace Redis for these operations.

Proxy logins fail closed when Redis cannot confirm a safe player-session claim.
If Redis becomes unavailable after login, CraftRelay stops trusting its
unrefreshed session lease and disconnects a player before that lease can be
mistaken for current ownership. Configure Redis availability and monitoring
separately; changing heartbeat and TTL defaults requires measurements for the
target deployment.
