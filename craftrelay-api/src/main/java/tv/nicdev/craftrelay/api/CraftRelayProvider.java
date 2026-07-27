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
package tv.nicdev.craftrelay.api;

import java.util.Optional;

/**
 * Platform-neutral access point for a lifecycle-bound CraftRelay API.
 *
 * <p>The provider is not a global singleton. Platform adapters expose their own
 * provider instance. The result is empty while the owning node is starting or
 * stopping and after it has stopped.
 */
public interface CraftRelayProvider {

    /**
     * Returns the currently available API.
     *
     * @return the API, or empty outside the available lifecycle state
     */
    Optional<CraftRelayApi> api();
}
