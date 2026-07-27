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
package tv.nicdev.craftrelay.example.paper;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.example.ExampleCommandService;
import tv.nicdev.craftrelay.example.ExamplePresentation;

/** Paper adapter for the CraftRelay developer example. */
public final class CraftRelayExamplePaperPlugin extends JavaPlugin
        implements Listener, TabCompleter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final PaperApiBindings bindings = new PaperApiBindings();

    /** Creates the Paper example plugin. */
    public CraftRelayExamplePaperPlugin() {
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command =
                Objects.requireNonNull(getCommand("craftrelayexample"), "craftrelayexample command");
        command.setExecutor(this);
        command.setTabCompleter(this);
        RegisteredServiceProvider<CraftRelayApi> registration =
                getServer().getServicesManager().getRegistration(CraftRelayApi.class);
        if (registration != null) {
            bind(registration.getProvider());
        }
    }

    @Override
    public void onDisable() {
        bindings.clear();
    }

    /**
     * Binds a newly published CraftRelay service.
     *
     * @param event Bukkit service event
     */
    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == CraftRelayApi.class) {
            bind((CraftRelayApi) event.getProvider().getProvider());
        }
    }

    /**
     * Removes only the service instance that was actually unpublished.
     *
     * @param event Bukkit service event
     */
    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() != CraftRelayApi.class) {
            return;
        }
        CraftRelayApi removed = (CraftRelayApi) event.getProvider().getProvider();
        bindings.unbind(removed);
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        ExampleCommandService service = bindings.commands().orElse(null);
        if (service == null) {
            sender.sendMessage(render("CraftRelay is not available yet."));
            return true;
        }
        service.execute(arguments).whenComplete((lines, failure) -> {
            Runnable deliver = () -> {
                if (failure != null) {
                    sender.sendMessage(render("CraftRelay example command failed."));
                } else {
                    lines.stream().map(CraftRelayExamplePaperPlugin::render)
                            .forEach(sender::sendMessage);
                }
            };
            try {
                getServer().getScheduler().runTask(this, deliver);
            } catch (RuntimeException ignored) {
                // The plugin is already disabled; no platform object is touched off-thread.
            }
        });
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
        return ExampleCommandService.suggestions(arguments);
    }

    private void bind(CraftRelayApi availableApi) {
        bindings.bind(Objects.requireNonNull(availableApi, "availableApi"));
    }

    private static Component render(String message) {
        return MINI_MESSAGE.deserialize(
                ExamplePresentation.COMMAND,
                Placeholder.unparsed(ExamplePresentation.MESSAGE_PLACEHOLDER, message));
    }
}
