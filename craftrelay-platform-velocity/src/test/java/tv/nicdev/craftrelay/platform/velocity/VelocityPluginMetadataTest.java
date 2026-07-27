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
package tv.nicdev.craftrelay.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VelocityPluginMetadataTest {

    @Test
    void annotationProcessorGeneratesVersionedMetadata() throws IOException {
        try (var input =
                getClass().getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"id\":\"craftrelay\""));
            assertTrue(metadata.contains(
                    "\"version\":\"" + System.getProperty("craftrelayVersion") + '"'));
            assertTrue(metadata.contains("\"authors\":" + expectedJsonAuthors()));
            assertTrue(metadata.contains(
                    "\"main\":\"tv.nicdev.craftrelay.platform.velocity.CraftRelayVelocityPlugin\""));
        }
    }

    private static String expectedJsonAuthors() {
        return java.util.Arrays.stream(
                        System.getProperty("craftrelayAuthors").split(","))
                .map(author -> "\"" + author + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
