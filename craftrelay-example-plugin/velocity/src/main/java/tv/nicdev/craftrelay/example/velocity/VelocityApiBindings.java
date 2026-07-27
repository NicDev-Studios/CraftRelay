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
package tv.nicdev.craftrelay.example.velocity;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.example.ExampleCommandService;

/**
 * Owns one coherent Velocity API binding and its broadcast subscription.
 *
 * <p>Binding and closing are serialized, while command execution can read the
 * immutable current binding without locking.
 */
final class VelocityApiBindings implements AutoCloseable {

    private static final System.Logger LOGGER =
            System.getLogger(VelocityApiBindings.class.getName());

    private final Consumer<? super GlobalBroadcastMessage> broadcastListener;
    private final AtomicReference<Binding> current = new AtomicReference<>();

    private boolean closed;

    VelocityApiBindings(Consumer<? super GlobalBroadcastMessage> broadcastListener) {
        this.broadcastListener =
                Objects.requireNonNull(broadcastListener, "broadcastListener");
    }

    synchronized void bind(CraftRelayApi api) {
        CraftRelayApi validated = Objects.requireNonNull(api, "api");
        if (closed) {
            return;
        }
        Binding existing = current.get();
        if (existing != null && existing.api() == validated) {
            return;
        }

        Subscription subscription;
        try {
            subscription = validated.subscribe(
                    GlobalBroadcastMessage.class, broadcastListener);
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not subscribe the example plugin to broadcasts",
                    failure);
            return;
        }

        Binding replacement = new Binding(
                validated,
                new ExampleCommandService(validated),
                subscription);
        Binding previous = current.getAndSet(replacement);
        if (previous != null) {
            previous.subscription().close();
        }
    }

    Optional<Binding> current() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Binding previous = current.getAndSet(null);
        if (previous != null) {
            previous.subscription().close();
        }
    }

    record Binding(
            CraftRelayApi api,
            ExampleCommandService commands,
            Subscription subscription) {

        Binding {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(commands, "commands");
            Objects.requireNonNull(subscription, "subscription");
        }
    }
}
