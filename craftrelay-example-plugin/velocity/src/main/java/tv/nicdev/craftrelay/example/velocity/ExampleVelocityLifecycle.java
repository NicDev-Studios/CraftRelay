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
package tv.nicdev.craftrelay.example.velocity;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayProvider;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.example.ExampleCommandService;
import tv.nicdev.craftrelay.example.ExamplePresentation;

/** Owns the Velocity example command and its current API subscription. */
public final class ExampleVelocityLifecycle {

    private static final System.Logger LOGGER =
            System.getLogger(ExampleVelocityLifecycle.class.getName());
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Object plugin;
    private final ProxyServer server;
    private final VelocityDevAdmins devAdmins;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final VelocityApiBindings bindings;

    private CommandMeta commandMeta;

    /**
     * Creates a Velocity example lifecycle.
     *
     * @param plugin owning plugin
     * @param server active proxy
     */
    public ExampleVelocityLifecycle(Object plugin, ProxyServer server) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.devAdmins = VelocityDevAdmins.fromEnvironment();
        this.bindings = new VelocityApiBindings(this::broadcast);
    }

    /** Registers the command and binds an already available provider. */
    public void start() {
        commandMeta = server.getCommandManager()
                .metaBuilder("craftrelayexample")
                .aliases("crelay")
                .plugin(plugin)
                .build();
        server.getCommandManager().register(commandMeta, new ExampleCommand());
        findProvider().flatMap(CraftRelayProvider::api).ifPresent(this::bind);
    }

    /**
     * Binds the API announced by CraftRelay's ready event.
     *
     * @param api available API
     */
    public void ready(CraftRelayApi api) {
        CraftRelayApi validated = Objects.requireNonNull(api, "api");
        if (!stopping.get()) {
            bind(validated);
        }
    }

    /** Closes subscriptions and unregisters the command. */
    public synchronized void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        bindings.close();
        CommandMeta registered = commandMeta;
        commandMeta = null;
        if (registered != null) {
            server.getCommandManager().unregister(registered);
        }
    }

    private Optional<CraftRelayProvider> findProvider() {
        return server.getPluginManager()
                .getPlugin("craftrelay")
                .flatMap(container -> container.getInstance())
                .filter(CraftRelayProvider.class::isInstance)
                .map(CraftRelayProvider.class::cast);
    }

    private synchronized void bind(CraftRelayApi api) {
        if (stopping.get()) {
            return;
        }
        bindings.bind(api);
    }

    private void broadcast(GlobalBroadcastMessage message) {
        try {
            server.getScheduler()
                    .buildTask(plugin, () -> server.getAllPlayers().forEach(
                            player -> player.sendMessage(render(
                                    ExamplePresentation.BROADCAST,
                                    message.content()))))
                    .schedule();
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not schedule an example broadcast",
                    failure);
        }
    }

    private final class ExampleCommand implements RawCommand {

        @Override
        public void execute(Invocation invocation) {
            VelocityApiBindings.Binding current = bindings.current().orElse(null);
            if (current == null) {
                invocation.source().sendMessage(render(
                        ExamplePresentation.COMMAND,
                        "CraftRelay is not available yet."));
                return;
            }
            current.commands().execute(executionArguments(invocation.arguments()))
                    .whenComplete((lines, failure) -> {
                try {
                    server.getScheduler()
                            .buildTask(plugin, () -> {
                                if (failure != null) {
                                    invocation.source().sendMessage(render(
                                            ExamplePresentation.COMMAND,
                                            "CraftRelay example command failed."));
                                } else {
                                    lines.stream()
                                            .map(line -> render(
                                                    ExamplePresentation.COMMAND, line))
                                            .forEach(invocation.source()::sendMessage);
                                }
                            })
                            .schedule();
                } catch (RuntimeException ignored) {
                    // The proxy is shutting down; do not touch the source off-thread.
                }
            });
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(ExampleCommandService.PERMISSION)
                    || invocation.source() instanceof Player player
                            && devAdmins.contains(player.getUsername());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return ExampleCommandService.suggestions(suggestionArguments(invocation.arguments()));
        }
    }

    private static String[] executionArguments(String rawArguments) {
        String stripped = rawArguments.strip();
        return stripped.isEmpty() ? new String[0] : stripped.split("\\s+");
    }

    private static String[] suggestionArguments(String rawArguments) {
        String strippedLeading = rawArguments.stripLeading();
        return strippedLeading.isEmpty()
                ? new String[] {""}
                : strippedLeading.split("\\s+", -1);
    }

    private static Component render(String template, String message) {
        return MINI_MESSAGE.deserialize(
                template,
                Placeholder.unparsed(ExamplePresentation.MESSAGE_PLACEHOLDER, message));
    }
}
