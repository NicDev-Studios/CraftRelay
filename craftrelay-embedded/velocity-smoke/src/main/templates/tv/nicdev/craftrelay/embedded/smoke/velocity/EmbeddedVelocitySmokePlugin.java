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
package tv.nicdev.craftrelay.embedded.smoke.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.slf4j.Logger;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelay;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelayNode;

/** Minimal Velocity host used only by the embedded SDK smoke topology. */
@Plugin(
        id = "craftrelay-embedded-velocity-smoke",
        name = "CraftRelay Embedded Velocity Smoke",
        version = "${version}",
        description = "Minimal CraftRelay Embedded SDK Velocity smoke host",
        authors = {${authors}})
public final class EmbeddedVelocitySmokePlugin {

    private final EmbeddedCraftRelayNode node;
    private final Logger logger;

    /**
     * Creates the smoke host and composes its embedded node.
     *
     * @param server Velocity proxy server
     * @param dataDirectory plugin data directory
     * @param logger plugin logger
     */
    @Inject
    public EmbeddedVelocitySmokePlugin(
            ProxyServer server, @DataDirectory Path dataDirectory, Logger logger) {
        this.logger = logger;
        node = EmbeddedCraftRelay.builder(dataDirectory, NetworkInstanceType.PROXY)
                .onlinePlayerCount(server::getPlayerCount)
                .startupLogger(logger::info)
                .diagnosticListener(event -> logger.debug(event.code()))
                .build();
    }

    /**
     * Starts the embedded node through Velocity's asynchronous continuation.
     *
     * @param event proxy initialization event
     * @return continuation that completes after startup
     */
    @Subscribe
    public EventTask onProxyInitialize(ProxyInitializeEvent event) {
        return EventTask.resumeWhenComplete(node.start().whenComplete((ignored, failure) -> {
            if (failure == null) {
                logger.info("Embedded CraftRelay is ready.");
            } else {
                logger.error("Embedded CraftRelay could not start.");
            }
        }));
    }

    /**
     * Stops the embedded node through Velocity's asynchronous continuation.
     *
     * @param event proxy shutdown event
     * @return continuation that completes after shutdown
     */
    @Subscribe
    public EventTask onProxyShutdown(ProxyShutdownEvent event) {
        return EventTask.resumeWhenComplete(node.stop());
    }
}
