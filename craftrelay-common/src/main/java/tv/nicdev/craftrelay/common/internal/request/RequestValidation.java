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

import java.time.Duration;
import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Shared validation for public and internal correlated-request boundaries.
 */
public final class RequestValidation {

    private RequestValidation() {
    }

    /**
     * Validates a request contract and returns its scheduler-safe timeout.
     *
     * @param request request message
     * @param responseType expected response type
     * @param timeout positive finite timeout
     * @return timeout in nanoseconds
     */
    public static long validateAndGetTimeoutNanos(
            NetworkMessage request,
            Class<? extends NetworkMessage> responseType,
            Duration timeout) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(timeout, "timeout");
        if (request.getClass() == responseType) {
            throw new IllegalArgumentException(
                    "request and response must use different concrete message types");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout is too large", failure);
        }
    }
}
