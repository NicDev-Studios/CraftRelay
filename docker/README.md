# Local CraftRelay network

This Compose environment starts:

- Redis 7.4.2
- two Paper 1.20.6 backends (`paper-lobby-1`, `paper-lobby-2`)
- two Velocity proxies (`proxy-docker-1`, `proxy-docker-2`)

Velocity 1 is published on `localhost:25565` and Velocity 2 on
`localhost:25566`. Connect Minecraft 1.20.6 clients to either address.

## Start

Docker Desktop must be running. From the repository root:

```powershell
.\docker\up.ps1
```

The script builds both shaded plugin JARs and then waits for the Compose
services to become healthy.

Velocity can switch between `lobby` and `lobby-2`. The second proxy is useful
for testing distributed proxy presence and duplicate-session fencing.

Follow the logs with:

```powershell
docker compose -f .\docker\compose.yml logs -f
```

## Stop

```powershell
.\docker\down.ps1
```

Server data remains in named Docker volumes. To also discard worlds and
generated runtime data:

```powershell
docker compose -f .\docker\compose.yml down -v
```

The last command is destructive for this local test environment.
