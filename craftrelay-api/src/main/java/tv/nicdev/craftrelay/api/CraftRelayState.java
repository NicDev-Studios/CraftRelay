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

/**
 * Lifecycle state of the local CraftRelay API implementation.
 */
public enum CraftRelayState {
    /** The implementation is being initialized and cannot serve requests yet. */
    INITIALIZING,
    /** The implementation is ready to serve requests. */
    AVAILABLE,
    /** The implementation is shutting down and no longer accepts new work. */
    STOPPING,
    /** The implementation has stopped permanently. */
    STOPPED
}
