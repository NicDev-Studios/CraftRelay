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
package tv.nicdev.craftrelay.common.internal.runtime;

import java.util.Objects;
import java.util.Optional;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;

/**
 * Immutable identity used for local target matching.
 *
 * @param instanceId network-unique instance ID
 * @param instanceType local instance type
 * @param group optional routing group
 */
public record LocalInstanceIdentity(
        String instanceId, NetworkInstanceType instanceType, Optional<String> group) {

    /** Creates a validated local identity. */
    public LocalInstanceIdentity {
        instanceId = requireText(instanceId, "instanceId");
        instanceType = Objects.requireNonNull(instanceType, "instanceType");
        group = Objects.requireNonNull(group, "group").map(value -> requireText(value, "group"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
