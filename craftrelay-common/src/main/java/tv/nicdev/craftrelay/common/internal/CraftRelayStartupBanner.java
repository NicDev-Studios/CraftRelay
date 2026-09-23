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
package tv.nicdev.craftrelay.common.internal;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Provides the CraftRelay startup banner for platform adapters and embedded hosts.
 *
 * <p>The banner deliberately uses printable ASCII only. This keeps its width stable in
 * Minecraft consoles, Docker log viewers, and Markdown renderers whose fallback fonts do not
 * agree on the width of Unicode box-drawing characters.
 */
public final class CraftRelayStartupBanner {

    private static final List<String> LINES = List.of(
            "   ____ ____      _    _____ _____ ____  _____ _        _ __   __",
            "  / ___|  _ \\    / \\  |  ___|_   _|  _ \\| ____| |      / \\\\ \\ / /",
            " | |   | |_) |  / _ \\ | |_    | | | |_) |  _| | |     / _ \\\\ V /",
            " | |___|  _ <  / ___ \\|  _|   | | |  _ <| |___| |___ / ___ \\| |",
            "  \\____|_| \\_\\/_/   \\_\\_|     |_| |_| \\_\\_____|_____/_/   \\_\\_|");

    private CraftRelayStartupBanner() {
    }

    /**
     * Returns the immutable lines of the CraftRelay startup banner.
     *
     * @return banner lines in display order
     */
    public static List<String> lines() {
        return LINES;
    }

    /**
     * Sends every banner line to the supplied platform logger in display order.
     *
     * @param sink logger callback
     */
    public static void writeTo(Consumer<? super String> sink) {
        Objects.requireNonNull(sink, "sink");
        LINES.forEach(sink);
    }
}
