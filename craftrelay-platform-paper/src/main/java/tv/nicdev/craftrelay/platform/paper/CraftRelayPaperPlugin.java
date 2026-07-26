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
package tv.nicdev.craftrelay.platform.paper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;
import tv.nicdev.craftrelay.common.internal.presence.PlayerOwnershipListener;
import tv.nicdev.craftrelay.transport.redis.RedisCraftRelayNodeFactory;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayConfigFiles;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/** Paper entry point for one CraftRelay server node. */
public final class CraftRelayPaperPlugin extends JavaPlugin {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile CraftRelayNode node;
    private volatile CraftRelayRedisConfig settings;

    /** Creates the Paper plugin entry point. */
    public CraftRelayPaperPlugin() {
    }

    @Override
    public void onEnable() {
        PaperPlayerCounter playerCounter =
                new PaperPlayerCounter(getServer().getOnlinePlayers().size());
        getServer().getPluginManager().registerEvents(playerCounter, this);

        try {
            settings = CraftRelayConfigFiles.loadOrCreate(getDataFolder().toPath());
            node = RedisCraftRelayNodeFactory.create(
                    settings,
                    NetworkInstanceType.SERVER,
                    playerCounter,
                    PlayerOwnershipListener.NOOP);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Could not load CraftRelay configuration", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        node.start().whenComplete((ignored, failure) -> completeStartOnServerThread(failure));
    }

    @Override
    public void onDisable() {
        stopping.set(true);
        getServer().getServicesManager().unregisterAll(this);

        CraftRelayNode current = node;
        if (current == null) {
            return;
        }
        CompletableFuture<Void> shutdown = current.close();
        Duration timeout =
                settings == null ? DEFAULT_SHUTDOWN_TIMEOUT : settings.shutdownTimeout();
        try {
            shutdown.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.WARNING, "CraftRelay shutdown was interrupted", failure);
        } catch (ExecutionException failure) {
            getLogger().log(
                    Level.WARNING,
                    "CraftRelay shutdown failed",
                    AsyncFailures.unwrap(failure));
        } catch (TimeoutException failure) {
            getLogger().log(
                    Level.WARNING,
                    "CraftRelay shutdown exceeded " + timeout,
                    failure);
        }
    }

    private void completeStartOnServerThread(Throwable failure) {
        try {
            getServer().getScheduler().runTask(this, () -> completeStart(failure));
        } catch (RuntimeException schedulingFailure) {
            CraftRelayNode current = node;
            if (current != null) {
                current.close();
            }
            getLogger().log(
                    Level.SEVERE,
                    "Could not finish CraftRelay startup on the server thread",
                    schedulingFailure);
        }
    }

    private void completeStart(Throwable failure) {
        CraftRelayNode current = node;
        if (stopping.get() || !isEnabled()) {
            if (current != null) {
                current.close();
            }
            return;
        }
        if (failure != null) {
            getLogger().log(
                    Level.SEVERE,
                    "CraftRelay failed to start",
                    AsyncFailures.unwrap(failure));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CraftRelayApi api = current.api();
        getServer().getServicesManager()
                .register(CraftRelayApi.class, api, this, ServicePriority.Normal);
        getLogger().info("CraftRelay is available as instance " + settings.instanceId());
    }
}
