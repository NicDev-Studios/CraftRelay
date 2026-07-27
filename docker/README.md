# Local CraftRelay network

This Compose environment starts:

- Redis 7.4.2
- a configurable number of Paper backends (`paper-1`, `paper-2`, ...)
- a configurable number of Velocity proxies (`velocity-1`, `velocity-2`, ...)
- CraftRelay and the matching Example plugin on every Minecraft process

Two Paper servers and two Velocity proxies are started by default. Velocity
ports start at `VELOCITY_PORT` and increase by one for every additional proxy.

## Start

Docker Engine or Docker Desktop must be running. The recommended command is
identical on Windows, Linux, and macOS:

```shell
./gradlew devUp
```

On Windows without a Unix-like shell, use `gradlew.bat devUp`. The task builds
all four installable plugin JARs and waits for the Compose services to become
healthy. Use `/crelay instances` in a console or as an operator to inspect all
four nodes.

## Configuration

Every setting has a default, so no local file is required. To customize the
environment, copy the tracked template:

```shell
cp docker/.env.example docker/.env
```

PowerShell equivalent:

```powershell
Copy-Item docker/.env.example docker/.env
```

`docker/.env` is ignored by Git. The most important setting is:

```dotenv
MINECRAFT_VERSION=1.20.6
PAPER_COUNT=2
VELOCITY_COUNT=2
PAPER_OPS=YourMinecraftName
```

`PAPER_COUNT` and `VELOCITY_COUNT` accept values from 1 through 10. Gradle
generates unique service names, CraftRelay instance IDs, volumes, proxy ports,
and Velocity backend lists before Docker Compose starts.

Every Paper server uses the selected version. Values supported by the
`itzg/minecraft-server` Paper image can be selected; CraftRelay itself targets
Paper API 1.20.6, so older server versions are not supported. The same file can
change Velocity's version, memory limits, and the first published proxy port.

`PAPER_OPS` is synchronized to both Paper servers whenever they start. The
same usernames receive access to the Example plugin's
`craftrelay.example.admin` command on both Velocity proxies. It accepts a
Minecraft username, UUID, or several comma-separated entries:

```dotenv
PAPER_OPS=NicDev,SecondDeveloper
```

An empty value means that the developer topology provisions no operators or
Velocity development admins. After changing it, run `devUp` again so Compose
recreates the affected containers. This environment-based Velocity fallback
exists only for the local Docker topology; regular installations should grant
`craftrelay.example.admin` through their Velocity permission provider.

Velocity can switch between `paper-1` and `paper-2`. The second proxy is useful
for testing distributed proxy presence and duplicate-session fencing.

## Automated smoke test

The smoke test uses its own Compose project, builds all four installable JARs,
waits for every configured Redis lease, invokes `crelay instances` through
Paper's internal-only RCON connection, verifies every generated node ID, and
publishes a test broadcast:

```shell
./gradlew devSmoke
```

On failure it prints container state and recent logs. Its containers are always
stopped in a `finally` path. RCON has no published host port and is enabled only
inside this developer topology.

Follow the logs with:

```shell
./gradlew devLogs
```

## Stop

```shell
./gradlew devDown
```

Server data remains in named Docker volumes. To also discard worlds and
generated runtime data:

```shell
docker compose --env-file docker/.generated/.env -f docker/.generated/compose.yml down -v
```

The last command is destructive for this local test environment.

No platform-specific scripts are required. Gradle invokes Docker Compose
directly and implements the complete smoke-test lifecycle on the JVM.
