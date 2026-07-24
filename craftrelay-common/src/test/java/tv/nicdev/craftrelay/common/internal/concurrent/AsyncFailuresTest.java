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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class AsyncFailuresTest {

    @Test
    void unwrapsNestedFutureFailuresAndPreservesNull() {
        IllegalStateException cause = new IllegalStateException("root");
        CompletionException wrapped =
                new CompletionException(new ExecutionException(cause));

        assertSame(cause, AsyncFailures.unwrap(wrapped));
        assertNull(AsyncFailures.unwrapNullable(null));
        assertThrows(NullPointerException.class, () -> AsyncFailures.unwrap(null));
    }

    @Test
    void mergesDistinctFailuresWithoutDuplicatingSuppressedEntries() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary =
                new IllegalArgumentException("secondary");

        assertSame(primary, AsyncFailures.merge(primary, secondary));
        assertArrayEquals(
                new Throwable[] {secondary}, primary.getSuppressed());
        assertSame(primary, AsyncFailures.merge(primary, primary));
        assertArrayEquals(
                new Throwable[] {secondary}, primary.getSuppressed());
        assertSame(secondary, AsyncFailures.merge(null, secondary));
        assertNull(AsyncFailures.merge(null, null));
    }
}
