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

/**
 * Factory for the standard CraftRelay network targets.
 */
public final class NetworkTargets {

    private static final NetworkTarget ALL_INSTANCES = new NetworkTarget.AllInstances();
    private static final NetworkTarget ALL_PROXIES = new NetworkTarget.AllProxies();
    private static final NetworkTarget ALL_SERVERS = new NetworkTarget.AllServers();

    private NetworkTargets() {
    }

    /**
     * Targets all participating instances.
     *
     * @return shared all-instances target
     */
    public static NetworkTarget allInstances() {
        return ALL_INSTANCES;
    }

    /**
     * Targets all proxy instances.
     *
     * @return shared all-proxies target
     */
    public static NetworkTarget allProxies() {
        return ALL_PROXIES;
    }

    /**
     * Targets all backend-server instances.
     *
     * @return shared all-servers target
     */
    public static NetworkTarget allServers() {
        return ALL_SERVERS;
    }

    /**
     * Targets one instance.
     *
     * @param id network-unique instance ID
     * @return validated instance target
     */
    public static NetworkTarget instance(String id) {
        return new NetworkTarget.Instance(id);
    }

    /**
     * Targets all instances in a routing group.
     *
     * @param name routing-group name
     * @return validated group target
     */
    public static NetworkTarget group(String name) {
        return new NetworkTarget.Group(name);
    }
}
