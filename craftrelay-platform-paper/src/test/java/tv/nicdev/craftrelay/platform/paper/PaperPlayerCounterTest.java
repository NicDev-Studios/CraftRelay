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
package tv.nicdev.craftrelay.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaperPlayerCounterTest {

    @Test
    void tracksMainThreadMutationsForOffThreadReads() {
        PaperPlayerCounter counter = new PaperPlayerCounter(2);

        counter.playerJoined();
        assertEquals(3, counter.getAsInt());

        counter.playerQuit();
        counter.playerQuit();
        counter.playerQuit();
        counter.playerQuit();
        assertEquals(0, counter.getAsInt());
    }

    @Test
    void rejectsNegativeInitialCount() {
        assertThrows(IllegalArgumentException.class, () -> new PaperPlayerCounter(-1));
    }
}
