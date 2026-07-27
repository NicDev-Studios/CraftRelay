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
package tv.nicdev.craftrelay.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Optional;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayProvider;
import tv.nicdev.craftrelay.platform.velocity.internal.lifecycle.VelocityPluginLifecycle;

/** Velocity entry point for one CraftRelay proxy node. */
@Plugin(
        id = "craftrelay",
        name = "CraftRelay",
        version = "${version}",
        description = "Redis-backed Minecraft network synchronization",
        authors = {${authors}})
public final class CraftRelayVelocityPlugin implements CraftRelayProvider {

    private final VelocityPluginLifecycle lifecycle;

    /**
     * Creates the injected Velocity plugin instance.
     *
     * @param server active proxy
     * @param dataDirectory plugin-owned data directory
     */
    @Inject
    public CraftRelayVelocityPlugin(
            ProxyServer server, @DataDirectory Path dataDirectory) {
        lifecycle = new VelocityPluginLifecycle(this, server, dataDirectory);
    }

    @Override
    public Optional<CraftRelayApi> api() {
        return lifecycle.api();
    }

    /**
     * Starts the Redis node without blocking a Velocity event thread.
     *
     * @param event proxy initialization event
     * @return asynchronous startup continuation
     */
    @Subscribe
    public EventTask onProxyInitialize(ProxyInitializeEvent event) {
        return EventTask.resumeWhenComplete(lifecycle.start());
    }

    /**
     * Stops the node through Velocity's asynchronous shutdown continuation.
     *
     * @param event proxy shutdown event
     * @return asynchronous shutdown continuation, or {@code null} when already stopped
     */
    @Subscribe
    public EventTask onProxyShutdown(ProxyShutdownEvent event) {
        return lifecycle.stop().map(EventTask::resumeWhenComplete).orElse(null);
    }
}
