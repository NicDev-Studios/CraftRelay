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

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import tv.nicdev.craftrelay.platform.velocity.CraftRelayReadyEvent;

/** Velocity entry point for the CraftRelay developer example. */
@Plugin(
        id = "craftrelay-example",
        name = "CraftRelayExample",
        version = "${version}",
        description = "CraftRelay developer integration example",
        authors = {${authors}},
        dependencies = {@Dependency(id = "craftrelay")})
public final class CraftRelayExampleVelocityPlugin {

    private final ExampleVelocityLifecycle lifecycle;

    /**
     * Creates the injected example plugin.
     *
     * @param server active proxy
     */
    @Inject
    public CraftRelayExampleVelocityPlugin(ProxyServer server) {
        lifecycle = new ExampleVelocityLifecycle(this, server);
    }

    /**
     * Registers the example command.
     *
     * @param event proxy initialization event
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        lifecycle.start();
    }

    /**
     * Observes late CraftRelay availability.
     *
     * @param event CraftRelay ready event
     */
    @Subscribe
    public void onCraftRelayReady(CraftRelayReadyEvent event) {
        lifecycle.ready(event.api());
    }

    /**
     * Releases the command and subscription.
     *
     * @param event proxy shutdown event
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        lifecycle.stop();
    }
}
