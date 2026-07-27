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

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit local-development fallback for Velocity, which has no operator
 * concept corresponding to Paper's {@code ops.json}.
 */
final class VelocityDevAdmins {

    private static final String ENVIRONMENT_VARIABLE = "CRAFTRELAY_DEV_ADMINS";

    private final Set<String> usernames;

    private VelocityDevAdmins(Set<String> usernames) {
        this.usernames = Set.copyOf(usernames);
    }

    static VelocityDevAdmins fromEnvironment() {
        return parse(System.getenv(ENVIRONMENT_VARIABLE));
    }

    static VelocityDevAdmins parse(String configuredUsernames) {
        if (configuredUsernames == null || configuredUsernames.isBlank()) {
            return new VelocityDevAdmins(Set.of());
        }
        Set<String> usernames = Arrays.stream(configuredUsernames.split(","))
                .map(String::trim)
                .filter(username -> !username.isEmpty())
                .map(username -> username.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return new VelocityDevAdmins(usernames);
    }

    boolean contains(String username) {
        return usernames.contains(username.toLowerCase(Locale.ROOT));
    }
}
