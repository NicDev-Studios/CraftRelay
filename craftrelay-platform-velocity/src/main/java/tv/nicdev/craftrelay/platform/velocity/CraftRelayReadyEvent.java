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

import java.util.Objects;
import tv.nicdev.craftrelay.api.CraftRelayApi;

/**
 * Fired after the Velocity CraftRelay node becomes available.
 *
 * @param api stable public API view
 */
public record CraftRelayReadyEvent(CraftRelayApi api) {

    /** Creates a validated ready event. */
    public CraftRelayReadyEvent {
        api = Objects.requireNonNull(api, "api");
    }
}
