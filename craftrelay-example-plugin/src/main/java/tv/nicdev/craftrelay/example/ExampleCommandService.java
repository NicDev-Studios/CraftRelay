/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.nicdev.craftrelay.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTargets;

/**
 * Platform-neutral implementation of the example command.
 *
 * <p>Every potentially slow operation returns immediately as a future. Platform
 * adapters are responsible for scheduling result delivery onto their own
 * platform thread.
 */
public final class ExampleCommandService {

    /** Administrative permission used by both platform adapters. */
    public static final String PERMISSION = "craftrelay.example.admin";

    private static final List<String> SUBCOMMANDS =
            List.of("state", "instances", "player", "broadcast", "connect");

    private static final List<String> USAGE = List.of(
            "Usage: /crelay state",
            "Usage: /crelay instances",
            "Usage: /crelay player <uuid>",
            "Usage: /crelay broadcast <message>",
            "Usage: /crelay connect <uuid> <server-id>");

    private final CraftRelayApi api;

    /**
     * Creates command operations for one available API.
     *
     * @param api available CraftRelay API
     */
    public ExampleCommandService(CraftRelayApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Executes one command invocation.
     *
     * @param arguments command arguments without the command label
     * @return immutable output lines
     */
    public CompletableFuture<List<String>> execute(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        String[] copy = arguments.clone();
        if (copy.length == 0) {
            return completed(USAGE);
        }

        return switch (copy[0].toLowerCase(Locale.ROOT)) {
            case "state" -> exactArguments(copy, 1, () -> completed(
                    List.of("CraftRelay state: " + api.state())));
            case "instances" -> exactArguments(copy, 1, this::instances);
            case "player" -> exactArguments(copy, 2, () -> player(copy[1]));
            case "broadcast" -> atLeastArguments(copy, 2, () -> broadcast(copy));
            case "connect" -> exactArguments(copy, 3, () -> connect(copy[1], copy[2]));
            default -> completed(USAGE);
        };
    }

    /**
     * Returns deterministic, platform-neutral command suggestions.
     *
     * @param arguments command arguments without the command label
     * @return immutable suggestions for the current argument
     */
    public static List<String> suggestions(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        String[] copy = arguments.clone();
        if (copy.length == 0) {
            return SUBCOMMANDS;
        }
        if (copy.length != 1) {
            return List.of();
        }

        String prefix = copy[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(command -> command.startsWith(prefix))
                .toList();
    }

    private CompletableFuture<List<String>> instances() {
        return safely(api::instances)
                .thenApply(ExampleCommandService::formatInstances)
                .exceptionally(ExampleCommandService::formatFailure);
    }

    private CompletableFuture<List<String>> player(String rawPlayerId) {
        UUID playerId;
        try {
            playerId = UUID.fromString(rawPlayerId);
        } catch (IllegalArgumentException failure) {
            return completed(List.of("Invalid player UUID: " + rawPlayerId));
        }
        return safely(() -> api.player(playerId))
                .thenApply(result -> formatPlayer(playerId, result))
                .exceptionally(ExampleCommandService::formatFailure);
    }

    private CompletableFuture<List<String>> broadcast(String[] arguments) {
        String message = String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length));
        if (message.isBlank()) {
            return completed(List.of("Broadcast message must not be blank."));
        }
        return safely(() -> api.publish(
                        NetworkTargets.allProxies(), new GlobalBroadcastMessage(message)))
                .thenApply(ignored -> List.of("Broadcast published."))
                .exceptionally(ExampleCommandService::formatFailure);
    }

    private CompletableFuture<List<String>> connect(String rawPlayerId, String serverId) {
        UUID playerId;
        try {
            playerId = UUID.fromString(rawPlayerId);
        } catch (IllegalArgumentException failure) {
            return completed(List.of("Invalid player UUID: " + rawPlayerId));
        }
        if (serverId.isBlank()) {
            return completed(List.of("Server ID must not be blank."));
        }
        return safely(() -> api.publish(
                        NetworkTargets.allProxies(),
                        new PlayerConnectRequest(playerId, serverId)))
                .thenApply(ignored -> List.of(
                        "Connection request published for " + playerId + " to " + serverId + '.'))
                .exceptionally(ExampleCommandService::formatFailure);
    }

    private static List<String> formatInstances(Collection<NetworkInstance> instances) {
        if (instances.isEmpty()) {
            return List.of("No active CraftRelay instances.");
        }
        List<String> lines = new ArrayList<>(instances.size() + 1);
        lines.add("Active CraftRelay instances: " + instances.size());
        instances.stream()
                .sorted(java.util.Comparator.comparing(NetworkInstance::id))
                .map(instance -> "- " + instance.id()
                        + " [" + instance.type() + "]"
                        + instance.group().map(group -> " group=" + group).orElse("")
                        + " players=" + instance.onlinePlayerCount())
                .forEach(lines::add);
        return List.copyOf(lines);
    }

    private static List<String> formatPlayer(
            UUID playerId, Optional<NetworkPlayer> result) {
        return result
                .map(player -> List.of(
                        "Player " + player.username() + " (" + player.uniqueId() + ')',
                        "Proxy: " + player.proxyId(),
                        "Server: " + player.serverId().orElse("<none>"),
                        "Session: " + player.sessionId()))
                .orElseGet(() -> List.of("Player is not connected: " + playerId));
    }

    private static CompletableFuture<List<String>> exactArguments(
            String[] arguments,
            int expected,
            Supplier<CompletableFuture<List<String>>> operation) {
        return arguments.length == expected ? operation.get() : completed(USAGE);
    }

    private static CompletableFuture<List<String>> atLeastArguments(
            String[] arguments,
            int minimum,
            Supplier<CompletableFuture<List<String>>> operation) {
        return arguments.length >= minimum ? operation.get() : completed(USAGE);
    }

    private static <T> CompletableFuture<T> safely(
            Supplier<CompletableFuture<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get(), "API future");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static List<String> formatFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        String detail = cause.getMessage();
        return List.of(detail == null || detail.isBlank()
                ? "CraftRelay operation failed: " + cause.getClass().getSimpleName()
                : "CraftRelay operation failed: " + detail);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static CompletableFuture<List<String>> completed(List<String> lines) {
        return CompletableFuture.completedFuture(List.copyOf(lines));
    }
}
