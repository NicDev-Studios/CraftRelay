package tv.nicdev.craftrelay.example.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PaperExampleMetadataTest {

    @Test
    void metadataContainsDependencyCommandAndAuthors() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains(
                    "main: tv.nicdev.craftrelay.example.paper.CraftRelayExamplePaperPlugin"));
            assertTrue(metadata.contains(
                    "version: \"" + System.getProperty("craftrelayVersion") + '"'));
            assertTrue(metadata.contains("depend: [CraftRelay]"));
            assertTrue(metadata.contains("aliases: [crelay]"));
            assertTrue(metadata.contains("authors: " + expectedYamlAuthors()));
            assertFalse(metadata.contains("${"));
        }
    }

    private static String expectedYamlAuthors() {
        return java.util.Arrays.stream(
                        System.getProperty("craftrelayAuthors").split(","))
                .map(author -> "\"" + author + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
}
