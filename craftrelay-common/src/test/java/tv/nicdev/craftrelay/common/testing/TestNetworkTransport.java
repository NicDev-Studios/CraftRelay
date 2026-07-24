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
package tv.nicdev.craftrelay.common.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;
import tv.nicdev.craftrelay.common.transport.TransportListener;
import tv.nicdev.craftrelay.common.transport.TransportState;

public final class TestNetworkTransport implements NetworkTransport {

    private final Map<String, List<TransportListener>> listeners = new HashMap<>();
    private final List<byte[]> publishedPayloads = new ArrayList<>();
    private final AtomicInteger connectCalls = new AtomicInteger();

    private volatile TransportState state = TransportState.NEW;
    private CompletableFuture<Void> nextConnect;
    private CompletableFuture<Void> nextClose;
    private int remainingConnectFailures;
    private boolean failNextSubscriptionClose;

    public synchronized void holdNextConnect(CompletableFuture<Void> connection) {
        nextConnect = Objects.requireNonNull(connection, "connection");
    }

    public synchronized void failNextConnects(int count) {
        remainingConnectFailures = count;
    }

    public synchronized void holdNextClose(CompletableFuture<Void> close) {
        nextClose = Objects.requireNonNull(close, "close");
    }

    public synchronized void failNextSubscriptionClose() {
        failNextSubscriptionClose = true;
    }

    public int connectCalls() {
        return connectCalls.get();
    }

    public synchronized int listenerCount(String channel) {
        return listeners.getOrDefault(channel, List.of()).size();
    }

    public synchronized byte[] publishedPayload(int index) {
        return Arrays.copyOf(publishedPayloads.get(index), publishedPayloads.get(index).length);
    }

    public synchronized int publishedPayloadCount() {
        return publishedPayloads.size();
    }

    public void emit(String channel, byte[] payload) {
        List<TransportListener> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(listeners.getOrDefault(channel, List.of()));
        }
        for (TransportListener listener : snapshot) {
            listener.onMessage(channel, Arrays.copyOf(payload, payload.length));
        }
    }

    @Override
    public synchronized CompletableFuture<Void> connect() {
        connectCalls.incrementAndGet();
        state = TransportState.CONNECTING;
        if (remainingConnectFailures > 0) {
            remainingConnectFailures--;
            state = TransportState.NEW;
            return CompletableFuture.failedFuture(
                    new IllegalStateException("expected connection failure"));
        }
        CompletableFuture<Void> connection =
                nextConnect == null ? CompletableFuture.completedFuture(null) : nextConnect;
        nextConnect = null;
        connection.whenComplete((ignored, failure) -> {
            synchronized (TestNetworkTransport.this) {
                state = failure == null ? TransportState.CONNECTED : TransportState.NEW;
            }
        });
        return connection;
    }

    @Override
    public CompletableFuture<Void> publish(String channel, byte[] payload) {
        Objects.requireNonNull(channel, "channel");
        byte[] copy = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        synchronized (this) {
            if (state != TransportState.CONNECTED) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("transport is not connected"));
            }
            publishedPayloads.add(copy);
        }
        emit(channel, copy);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized Subscription subscribe(String channel, TransportListener listener) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(listener, "listener");
        if (state == TransportState.CLOSING || state == TransportState.CLOSED) {
            throw new IllegalStateException("transport is closing or closed");
        }
        listeners.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(listener);
        return Subscription.create(() -> cancelSubscription(channel, listener));
    }

    @Override
    public TransportState state() {
        return state;
    }

    @Override
    public synchronized CompletableFuture<Void> close() {
        state = TransportState.CLOSING;
        listeners.clear();
        CompletableFuture<Void> close =
                nextClose == null ? CompletableFuture.completedFuture(null) : nextClose;
        nextClose = null;
        close.whenComplete((ignored, failure) -> {
            synchronized (TestNetworkTransport.this) {
                state = TransportState.CLOSED;
            }
        });
        return close;
    }

    private synchronized void remove(String channel, TransportListener listener) {
        List<TransportListener> channelListeners = listeners.get(channel);
        if (channelListeners == null) {
            return;
        }
        channelListeners.remove(listener);
        if (channelListeners.isEmpty()) {
            listeners.remove(channel);
        }
    }

    private void cancelSubscription(String channel, TransportListener listener) {
        remove(channel, listener);
        synchronized (this) {
            if (failNextSubscriptionClose) {
                failNextSubscriptionClose = false;
                throw new IllegalStateException("expected subscription close failure");
            }
        }
    }
}
