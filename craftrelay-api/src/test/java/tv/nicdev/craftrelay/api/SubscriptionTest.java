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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SubscriptionTest {

    @Test
    void closesOnlyOnce() {
        AtomicInteger cancellations = new AtomicInteger();
        Subscription subscription = Subscription.create(cancellations::incrementAndGet);

        assertFalse(subscription.isClosed());
        subscription.close();
        subscription.close();

        assertTrue(subscription.isClosed());
        assertEquals(1, cancellations.get());
    }

    @Test
    void closesOnlyOnceWhenCalledConcurrently() throws InterruptedException {
        AtomicInteger cancellations = new AtomicInteger();
        Subscription subscription = Subscription.create(cancellations::incrementAndGet);
        CountDownLatch start = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> closeAfter(start, subscription));
        Thread second = Thread.ofPlatform().start(() -> closeAfter(start, subscription));

        start.countDown();
        first.join();
        second.join();

        assertEquals(1, cancellations.get());
    }

    @Test
    void rejectsNullCancellation() {
        assertThrows(NullPointerException.class, () -> Subscription.create(null));
    }

    private static void closeAfter(CountDownLatch start, Subscription subscription) {
        try {
            start.await();
            subscription.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
