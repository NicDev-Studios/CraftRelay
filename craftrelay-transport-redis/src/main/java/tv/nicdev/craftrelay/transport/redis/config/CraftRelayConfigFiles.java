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
package tv.nicdev.craftrelay.transport.redis.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared creation and loading of the platform {@code config.yml}. */
public final class CraftRelayConfigFiles {

    /** Configuration filename inside each platform data directory. */
    public static final String CONFIG_FILE_NAME = "config.yml";

    private static final String DEFAULT_RESOURCE = "/craftrelay-default-config.yml";

    private CraftRelayConfigFiles() {
    }

    /**
     * Creates the default file if needed and loads it strictly.
     *
     * @param dataDirectory platform-owned plugin data directory
     * @return validated configuration
     * @throws IOException if creating or reading the file fails
     */
    public static CraftRelayRedisConfig loadOrCreate(Path dataDirectory) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve(CONFIG_FILE_NAME);
        if (Files.notExists(configPath)) {
            copyDefault(configPath);
        }
        return new YamlCraftRelayConfigLoader().load(configPath);
    }

    private static void copyDefault(Path target) throws IOException {
        try (InputStream input =
                CraftRelayConfigFiles.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw new IOException("Embedded CraftRelay configuration is missing");
            }
            try {
                Files.copy(input, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another startup path won the create-if-absent race.
            }
        }
    }
}
