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
package tv.nicdev.craftrelay.api.target;

import java.util.Objects;

/**
 * Closed set of destinations to which a network message may be routed.
 */
public sealed interface NetworkTarget
        permits NetworkTarget.AllInstances,
                NetworkTarget.AllProxies,
                NetworkTarget.AllServers,
                NetworkTarget.Instance,
                NetworkTarget.Group {

    /** Target selecting every CraftRelay instance. */
    record AllInstances() implements NetworkTarget {
    }

    /** Target selecting every proxy instance. */
    record AllProxies() implements NetworkTarget {
    }

    /** Target selecting every backend-server instance. */
    record AllServers() implements NetworkTarget {
    }

    /**
     * Target selecting one instance.
     *
     * @param id network-unique instance ID
     */
    record Instance(String id) implements NetworkTarget {
        /** Creates a validated instance target. */
        public Instance {
            id = requireText(id, "id");
        }
    }

    /**
     * Target selecting every instance in a routing group.
     *
     * @param name routing-group name
     */
    record Group(String name) implements NetworkTarget {
        /** Creates a validated group target. */
        public Group {
            name = requireText(name, "name");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
