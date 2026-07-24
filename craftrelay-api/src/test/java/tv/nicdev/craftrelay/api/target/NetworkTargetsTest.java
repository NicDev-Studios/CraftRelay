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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NetworkTargetsTest {

    @Test
    void reusesStatelessTargets() {
        assertSame(NetworkTargets.allInstances(), NetworkTargets.allInstances());
        assertSame(NetworkTargets.allProxies(), NetworkTargets.allProxies());
        assertSame(NetworkTargets.allServers(), NetworkTargets.allServers());
    }

    @Test
    void createsValueTargets() {
        NetworkTarget.Instance instance =
                assertInstanceOf(NetworkTarget.Instance.class, NetworkTargets.instance("proxy-1"));
        NetworkTarget.Group group =
                assertInstanceOf(NetworkTarget.Group.class, NetworkTargets.group("eu"));

        assertEquals("proxy-1", instance.id());
        assertEquals("eu", group.name());
        assertEquals(new NetworkTarget.Instance("proxy-1"), instance);
    }

    @Test
    void rejectsMissingTargetValues() {
        assertThrows(NullPointerException.class, () -> NetworkTargets.instance(null));
        assertThrows(IllegalArgumentException.class, () -> NetworkTargets.instance(" "));
        assertThrows(NullPointerException.class, () -> NetworkTargets.group(null));
        assertThrows(IllegalArgumentException.class, () -> NetworkTargets.group(""));
    }
}
