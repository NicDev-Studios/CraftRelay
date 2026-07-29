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
package tv.nicdev.craftrelay.common.internal.request;

/**
 * Capacity settings for correlated requests.
 *
 * @param maximumPendingRequests maximum requests awaiting a response
 */
public record RequestRuntimeConfig(int maximumPendingRequests) {

    /** Largest supported number of pending requests. */
    public static final int MAXIMUM_PENDING_REQUESTS = 65_536;

    /** Default maximum number of requests awaiting responses. */
    public static final int DEFAULT_MAXIMUM_PENDING_REQUESTS = 4_096;

    /** Creates validated request settings. */
    public RequestRuntimeConfig {
        if (maximumPendingRequests <= 0
                || maximumPendingRequests > MAXIMUM_PENDING_REQUESTS) {
            throw new IllegalArgumentException(
                    "maximumPendingRequests must be between 1 and "
                            + MAXIMUM_PENDING_REQUESTS);
        }
    }

    /**
     * Returns the production defaults.
     *
     * @return default request settings
     */
    public static RequestRuntimeConfig defaults() {
        return new RequestRuntimeConfig(DEFAULT_MAXIMUM_PENDING_REQUESTS);
    }
}
