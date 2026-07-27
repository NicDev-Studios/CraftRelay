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
package tv.nicdev.craftrelay.example.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.example.ExampleCommandService;

/**
 * Atomically owns the API and command service derived from it.
 *
 * <p>The immutable pair prevents readers from ever observing a command service
 * that belongs to a different API generation.
 */
final class PaperApiBindings {

    private final AtomicReference<Binding> current = new AtomicReference<>();

    void bind(CraftRelayApi api) {
        CraftRelayApi validated = Objects.requireNonNull(api, "api");
        current.set(new Binding(validated, new ExampleCommandService(validated)));
    }

    void unbind(CraftRelayApi api) {
        CraftRelayApi removed = Objects.requireNonNull(api, "api");
        current.updateAndGet(binding ->
                binding != null && binding.api() == removed ? null : binding);
    }

    Optional<ExampleCommandService> commands() {
        Binding binding = current.get();
        return binding == null ? Optional.empty() : Optional.of(binding.commands());
    }

    void clear() {
        current.set(null);
    }

    private record Binding(CraftRelayApi api, ExampleCommandService commands) {

        private Binding {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(commands, "commands");
        }
    }
}
