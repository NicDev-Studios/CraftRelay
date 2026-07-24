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

import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

final class TargetMatcher {

    private TargetMatcher() {
    }

    static boolean matches(NetworkTarget target, LocalInstanceIdentity identity) {
        return switch (target) {
            case NetworkTarget.AllInstances ignored -> true;
            case NetworkTarget.AllProxies ignored ->
                    identity.instanceType() == NetworkInstanceType.PROXY;
            case NetworkTarget.AllServers ignored ->
                    identity.instanceType() == NetworkInstanceType.SERVER;
            case NetworkTarget.Instance instance ->
                    identity.instanceId().equals(instance.id());
            case NetworkTarget.Group group ->
                    identity.group().filter(group.name()::equals).isPresent();
        };
    }
}
