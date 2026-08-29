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
package tv.nicdev.craftrelay.embedded.smoke.paper;

import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelay;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelayNode;

/** Minimal Paper host used only by the embedded SDK smoke topology. */
public final class EmbeddedPaperSmokePlugin extends JavaPlugin implements Listener {

    private final AtomicInteger onlinePlayers = new AtomicInteger();
    private EmbeddedCraftRelayNode node;

    /** Creates the smoke host. */
    public EmbeddedPaperSmokePlugin() {
    }

    @Override
    public void onEnable() {
        onlinePlayers.set(getServer().getOnlinePlayers().size());
        getServer().getPluginManager().registerEvents(this, this);
        try {
            node = EmbeddedCraftRelay.builder(
                            getDataFolder().toPath(), NetworkInstanceType.SERVER)
                    .onlinePlayerCount(onlinePlayers::get)
                    .startupLogger(getLogger()::info)
                    .diagnosticListener(event -> getLogger().fine(event.code()))
                    .build();
        } catch (RuntimeException failure) {
            getLogger().severe("Embedded CraftRelay configuration could not be loaded.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        node.start().whenComplete((ignored, failure) -> getServer().getScheduler().runTask(
                this,
                () -> {
                    if (failure == null) {
                        getLogger().info("Embedded CraftRelay is ready.");
                    } else {
                        getLogger().severe("Embedded CraftRelay could not start.");
                        getServer().getPluginManager().disablePlugin(this);
                    }
                }));
    }

    @Override
    public void onDisable() {
        EmbeddedCraftRelayNode current = node;
        if (current != null) {
            current.stop();
        }
    }

    /**
     * Updates the constant-time local count after a player joins.
     *
     * @param event Paper join event
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.incrementAndGet();
    }

    /**
     * Updates the constant-time local count after a player leaves.
     *
     * @param event Paper quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.updateAndGet(value -> Math.max(0, value - 1));
    }
}
