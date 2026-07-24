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

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DuplicateMessageCache {

    private final int capacity;
    private final Set<UUID> messageIds = new LinkedHashSet<>();

    DuplicateMessageCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized boolean markIfNew(UUID messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (messageIds.contains(messageId)) {
            return false;
        }
        if (messageIds.size() == capacity) {
            Iterator<UUID> iterator = messageIds.iterator();
            iterator.next();
            iterator.remove();
        }
        messageIds.add(messageId);
        return true;
    }

    synchronized int size() {
        return messageIds.size();
    }
}
