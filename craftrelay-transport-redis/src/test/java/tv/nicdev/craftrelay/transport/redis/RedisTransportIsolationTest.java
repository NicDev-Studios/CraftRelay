package tv.nicdev.craftrelay.transport.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisTransportIsolationTest {

    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "io/papermc/",
            "org/bukkit/",
            "com/velocitypowered/",
            "tv/nicdev/craftrelay/platform/");

    @Test
    void compiledTransportDoesNotReferenceMinecraftPlatforms()
            throws IOException, URISyntaxException {
        Path classesRoot = Path.of(LettuceRedisTransport.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

        try (var classFiles = Files.walk(classesRoot)) {
            classFiles
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(RedisTransportIsolationTest::assertNoForbiddenReferences);
        }
    }

    private static void assertNoForbiddenReferences(Path classFile) {
        try {
            String constantPool =
                    new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String forbiddenReference : FORBIDDEN_REFERENCES) {
                assertFalse(
                        constantPool.contains(forbiddenReference),
                        () -> classFile + " references forbidden namespace " + forbiddenReference);
            }
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect " + classFile, exception);
        }
    }
}
