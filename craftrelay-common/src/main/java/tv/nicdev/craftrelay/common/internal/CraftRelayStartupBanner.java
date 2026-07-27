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

/** Provides the CraftRelay startup banner for platform adapters. */
public final class CraftRelayStartupBanner {

    private static final List<String> LINES = List.of(
            "   ____            __ _   ____      _",
            "  / ___|_ __ __ _ / _| |_|  _ \\ ___| | __ _ _   _",
            " | |   | '__/ _` | |_| __| |_) / _ \\ |/ _` | | | |",
            " | |___| | | (_| |  _| |_|  _ <  __/ | (_| | |_| |",
            "  \\____|_|  \\__,_|_|  \\__|_| \\_\\___|_|\\__,_|\\__, |",
            "                                            |___/");

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
}
