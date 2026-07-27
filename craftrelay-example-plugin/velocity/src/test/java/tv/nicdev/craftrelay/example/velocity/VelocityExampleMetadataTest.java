package tv.nicdev.craftrelay.example.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VelocityExampleMetadataTest {

    @Test
    void metadataContainsDependencyMainClassAndAuthors() throws IOException {
        try (var input =
                getClass().getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"id\":\"craftrelay-example\""));
            assertTrue(metadata.contains(
                    "\"version\":\"" + System.getProperty("craftrelayVersion") + '"'));
            assertTrue(metadata.contains("\"id\":\"craftrelay\",\"optional\":false"));
            assertTrue(metadata.contains(
                    "\"main\":\"tv.nicdev.craftrelay.example.velocity.CraftRelayExampleVelocityPlugin\""));
            assertTrue(metadata.contains("\"authors\":" + expectedJsonAuthors()));
            assertFalse(metadata.contains("${"));
        }
    }

    private static String expectedJsonAuthors() {
        return java.util.Arrays.stream(
                        System.getProperty("craftrelayAuthors").split(","))
                .map(author -> "\"" + author + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
