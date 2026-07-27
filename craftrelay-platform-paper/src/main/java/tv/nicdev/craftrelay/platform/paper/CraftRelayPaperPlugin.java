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

import java.util.Optional;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayProvider;
import tv.nicdev.craftrelay.platform.paper.internal.lifecycle.PaperPluginLifecycle;

/** Paper entry point for one CraftRelay server node. */
public final class CraftRelayPaperPlugin extends JavaPlugin implements CraftRelayProvider {

    private PaperPluginLifecycle lifecycle;

    /** Creates the Paper plugin entry point. */
    public CraftRelayPaperPlugin() {
    }

    @Override
    public void onEnable() {
        lifecycle = new PaperPluginLifecycle(this);
        lifecycle.start();
    }

    @Override
    public void onDisable() {
        PaperPluginLifecycle current = lifecycle;
        if (current != null) {
            current.stop();
        }
    }

    @Override
    public Optional<CraftRelayApi> api() {
        PaperPluginLifecycle current = lifecycle;
        return current == null ? Optional.empty() : current.api();
    }
}
