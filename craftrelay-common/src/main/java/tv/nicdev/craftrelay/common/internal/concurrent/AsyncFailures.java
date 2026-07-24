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
package tv.nicdev.craftrelay.common.internal.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Shared failure handling for asynchronous lifecycle operations.
 */
public final class AsyncFailures {

    private AsyncFailures() {
    }

    /**
     * Removes nested future-completion wrappers from a non-null failure.
     *
     * @param failure failure to unwrap
     * @return first non-wrapper failure
     */
    public static Throwable unwrap(Throwable failure) {
        return Objects.requireNonNull(unwrapNullable(failure), "failure");
    }

    /**
     * Removes nested future-completion wrappers while preserving {@code null}.
     *
     * @param failure nullable failure to unwrap
     * @return first non-wrapper failure, or {@code null}
     */
    public static Throwable unwrapNullable(Throwable failure) {
        Throwable current = failure;
        while (current != null
                && (current instanceof CompletionException
                        || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Retains a primary failure and adds a distinct secondary failure as suppressed.
     *
     * @param first nullable primary failure
     * @param second nullable secondary failure
     * @return merged failure, or {@code null} when both inputs are {@code null}
     */
    public static Throwable merge(Throwable first, Throwable second) {
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }
}
