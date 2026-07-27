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
package tv.nicdev.craftrelay.platform.paper.internal.presence;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Main-thread-updated player counter safe for off-thread heartbeat reads. */
public final class PaperPlayerCounter implements Listener, IntSupplier {

    private final AtomicInteger onlinePlayers;

    /**
     * Creates a counter initialized from Bukkit's current online-player snapshot.
     *
     * @param initialOnlinePlayers initial number of connected players
     */
    public PaperPlayerCounter(int initialOnlinePlayers) {
        if (initialOnlinePlayers < 0) {
            throw new IllegalArgumentException("initialOnlinePlayers must not be negative");
        }
        onlinePlayers = new AtomicInteger(initialOnlinePlayers);
    }

    /**
     * Records a completed player join.
     *
     * @param event Bukkit join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        playerJoined();
    }

    /**
     * Records a player quit.
     *
     * @param event Bukkit quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        playerQuit();
    }

    /** Increments the counter. */
    public void playerJoined() {
        onlinePlayers.incrementAndGet();
    }

    /** Decrements the counter without allowing a negative result. */
    public void playerQuit() {
        onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
    }

    @Override
    public int getAsInt() {
        return onlinePlayers.get();
    }
}
