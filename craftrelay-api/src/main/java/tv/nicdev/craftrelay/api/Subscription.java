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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A message-listener registration that can be cancelled safely from any thread.
 *
 * <p>Closing a subscription is idempotent. The cancellation action runs at most once.
 */
public final class Subscription implements AutoCloseable {

    private final Runnable cancellation;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Subscription(Runnable cancellation) {
        this.cancellation = cancellation;
    }

    /**
     * Creates a subscription backed by the supplied cancellation action.
     *
     * @param cancellation action that unregisters the listener
     * @return a new open subscription
     */
    public static Subscription create(Runnable cancellation) {
        return new Subscription(Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Returns whether this subscription has been closed.
     *
     * @return {@code true} after the first call to {@link #close()}
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Cancels this subscription. Repeated and concurrent calls have no additional effect.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancellation.run();
        }
    }
}
