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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ListenerDispatcherTest {

    @Test
    void preservesOrderAndBoundsEachListenerIndependently() throws InterruptedException {
        ListenerDispatcher dispatcher = new ListenerDispatcher("dispatcher-test-");
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastReceived = new CountDownLatch(4);
        List<Integer> slowValues = new CopyOnWriteArrayList<>();
        List<Integer> fastValues = new CopyOnWriteArrayList<>();
        AtomicInteger overflows = new AtomicInteger();
        CountDownLatch overflowReported = new CountDownLatch(1);
        AtomicReference<Thread> overflowThread = new AtomicReference<>();
        Thread producerThread = Thread.currentThread();

        try {
            ListenerDispatcher.DispatchLane<Integer> slow = dispatcher.register(
                    2,
                    value -> {
                        slowValues.add(value);
                        if (value == 1) {
                            slowStarted.countDown();
                            awaitLatch(releaseSlow);
                        }
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    () -> {
                        overflowThread.set(Thread.currentThread());
                        overflows.incrementAndGet();
                        overflowReported.countDown();
                    });
            ListenerDispatcher.DispatchLane<Integer> fast = dispatcher.register(
                    4,
                    value -> {
                        fastValues.add(value);
                        fastReceived.countDown();
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    () -> {
                        throw new AssertionError("fast listener overflowed");
                    });

            assertTrue(slow.dispatch(1));
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
            assertTrue(slow.dispatch(2));
            assertTrue(slow.dispatch(3));
            assertFalse(slow.dispatch(4));
            for (int value = 1; value <= 4; value++) {
                assertTrue(fast.dispatch(value));
            }

            assertTrue(fastReceived.await(5, TimeUnit.SECONDS));
            assertTrue(overflowReported.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3, 4), fastValues);
            assertEquals(1, overflows.get());
            assertTrue(overflowThread.get().isVirtual());
            assertFalse(overflowThread.get() == producerThread);
            releaseSlow.countDown();
            awaitSize(slowValues, 3);
            assertEquals(List.of(1, 2, 3), slowValues);
        } finally {
            releaseSlow.countDown();
            dispatcher.close();
        }
    }

    @Test
    void listenerFailureDoesNotTerminateItsLane() throws InterruptedException {
        ListenerDispatcher dispatcher = new ListenerDispatcher("dispatcher-failure-test-");
        CountDownLatch secondDelivery = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            ListenerDispatcher.DispatchLane<Integer> lane = dispatcher.register(
                    4,
                    value -> {
                        if (value == 1) {
                            throw new IllegalStateException("expected");
                        }
                        secondDelivery.countDown();
                    },
                    failure -> failures.incrementAndGet(),
                    () -> {
                        throw new AssertionError("unexpected overflow");
                    });

            assertTrue(lane.dispatch(1));
            assertTrue(lane.dispatch(2));

            assertTrue(secondDelivery.await(5, TimeUnit.SECONDS));
            assertEquals(1, failures.get());
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void nonFatalListenerErrorDoesNotWedgeItsLane() throws InterruptedException {
        ListenerDispatcher dispatcher = new ListenerDispatcher("dispatcher-error-test-");
        CountDownLatch secondDelivery = new CountDownLatch(1);
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        try {
            ListenerDispatcher.DispatchLane<Integer> lane = dispatcher.register(
                    4,
                    value -> {
                        if (value == 1) {
                            throw new AssertionError("expected");
                        }
                        secondDelivery.countDown();
                    },
                    reportedFailure::set,
                    () -> {
                        throw new AssertionError("unexpected overflow");
                    });

            assertTrue(lane.dispatch(1));
            assertTrue(lane.dispatch(2));

            assertTrue(secondDelivery.await(5, TimeUnit.SECONDS));
            assertTrue(reportedFailure.get() instanceof AssertionError);
        } finally {
            dispatcher.close();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitSize(List<?> values, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (values.size() < expected && System.nanoTime() < deadline) {
            CountDownLatch delay = new CountDownLatch(1);
            delay.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(expected, values.size());
    }
}
