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
package tv.nicdev.craftrelay.platform.paper.internal.service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.CraftRelayApi;

/** Owns the lifecycle of CraftRelay's Bukkit service registration. */
public final class PaperApiService {

    private final JavaPlugin plugin;
    private final AtomicReference<CraftRelayApi> api = new AtomicReference<>();

    /**
     * Creates a service owner.
     *
     * @param plugin owning plugin
     */
    public PaperApiService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Publishes the API through Bukkit after startup has completed.
     *
     * @param availableApi available API
     */
    public void register(CraftRelayApi availableApi) {
        CraftRelayApi validated = Objects.requireNonNull(availableApi, "availableApi");
        if (!api.compareAndSet(null, validated)) {
            throw new IllegalStateException("CraftRelay API is already registered");
        }
        plugin.getServer()
                .getServicesManager()
                .register(CraftRelayApi.class, validated, plugin, ServicePriority.Normal);
    }

    /** Removes the service before shutdown begins. */
    public void unregister() {
        api.set(null);
        plugin.getServer().getServicesManager().unregisterAll(plugin);
    }

    /**
     * Returns the currently published API.
     *
     * @return available API, or empty outside its lifecycle window
     */
    public Optional<CraftRelayApi> api() {
        return Optional.ofNullable(api.get());
    }
}
