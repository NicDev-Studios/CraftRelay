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

Player login is fail-closed. Velocity completes a login only after Redis has
atomically claimed the UUID for that exact proxy session. A second proxy cannot
claim the same UUID while that lease exists. If Redis refreshes stop succeeding,
CraftRelay removes the local session before its last conservatively confirmed
lease can expire and schedules the matching Velocity connection for disconnect.
Stale disconnects are fenced by both session ID and node-lease token.

## Custom messages

Custom messages are explicit local registrations. Register the same identifier,
payload version, Java class, and JSON meaning on every node that sends or
receives the message:

```java
public record WarpRequest(UUID playerId, String warp) implements NetworkMessage {}

MessageType<WarpRequest> warpRequest =
        MessageType.of("myplugin", "warp_request", 1, WarpRequest.class);

MessagePayloadCodec<WarpRequest> codec = new MessagePayloadCodec<>() {
    @Override
    public byte[] encode(WarpRequest message) {
        return gson.toJson(message).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public WarpRequest decode(byte[] payload) {
        return gson.fromJson(
                new String(payload, StandardCharsets.UTF_8),
                WarpRequest.class);
    }
};

MessageRegistration<WarpRequest> requestRegistration =
        api.customMessaging().register(warpRequest, codec);

MessageRegistration<WarpResponse> responseRegistration =
        api.customMessaging().register(warpResponse, responseCodec);
```

The codec may use any JSON library owned by the integrating plugin. Its output
must contain exactly one UTF-8 JSON object. CraftRelay validates the object and
never obtains a Java class name from the network. The `craftrelay` namespace is
reserved for built-in messages.

`publish`, `subscribe`, and `request` work with the registered Java class. A
custom request handler additionally requires its response type to be registered:

```java
Subscription handler = api.customMessaging().handle(
        requestRegistration,
        responseRegistration,
        (request, context) ->
                warpService.find(request.playerId(), request.warp())
                        .thenApply(WarpResponse::new));
```

The handler runs away from Redis and platform I/O threads. CraftRelay correlates
the response and sends it back to the requesting instance automatically.
Exactly one handler may be active locally for a request registration. Closing
either message registration automatically closes dependent handlers. Foreign
registrations and registrations that are already closed are rejected. Handler
failures produce no remote error message; the caller's request completes with
its configured timeout.

Use a new positive payload version and a different Java class for an
incompatible schema. During a rolling upgrade, register both versions where
compatibility is required. CraftRelay does not distribute registrations or
migrate payloads. Protocol version 1 always includes the payload version.
Closing a registration prevents future encoding and decoding of that type;
already-accepted work may finish using its exact captured codec generation.

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
