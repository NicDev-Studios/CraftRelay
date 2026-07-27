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
package tv.nicdev.craftrelay.example;

/** Shared MiniMessage templates used by both example platform adapters. */
public final class ExamplePresentation {

    /** Placeholder name for dynamically supplied, unparsed text. */
    public static final String MESSAGE_PLACEHOLDER = "message";

    /** Standard command response presentation. */
    public static final String COMMAND =
            "<dark_gray>[<aqua>CraftRelay</aqua>]</dark_gray> <gray><message></gray>";

    /** Network broadcast presentation. */
    public static final String BROADCAST =
            "<dark_gray>[<aqua>CraftRelay</aqua>]</dark_gray>"
                    + " <gold>Broadcast</gold> <dark_gray>»</dark_gray>"
                    + " <white><message></white>";

    private ExamplePresentation() {
    }
}
