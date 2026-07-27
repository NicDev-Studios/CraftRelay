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
package tv.nicdev.craftrelay.example.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExampleVelocityLifecycleTest {

    @Test
    void startRegistersAliasesAndStopUnregistersExactlyOnce() {
        Object plugin = new Object();
        AtomicReference<String> primaryAlias = new AtomicReference<>();
        AtomicReference<List<String>> aliases = new AtomicReference<>();
        AtomicReference<Object> associatedPlugin = new AtomicReference<>();
        AtomicReference<CommandMeta> registeredMeta = new AtomicReference<>();
        AtomicReference<Command> registeredCommand = new AtomicReference<>();
        AtomicInteger unregisterCalls = new AtomicInteger();

        CommandMeta metadata = proxy(CommandMeta.class, (ignored, method, arguments) ->
                switch (method.getName()) {
                    case "getAliases" -> List.of("craftrelayexample", "crelay");
                    case "getHints" -> List.of();
                    case "getPlugin" -> plugin;
                    default -> defaultValue(method.getReturnType());
                });
        AtomicReference<CommandMeta.Builder> builderReference = new AtomicReference<>();
        CommandMeta.Builder builder = proxy(CommandMeta.Builder.class, (ignored, method, arguments) ->
                switch (method.getName()) {
                    case "aliases" -> {
                        aliases.set(List.of((String[]) arguments[0]));
                        yield builderReference.get();
                    }
                    case "plugin" -> {
                        associatedPlugin.set(arguments[0]);
                        yield builderReference.get();
                    }
                    case "hint" -> builderReference.get();
                    case "build" -> metadata;
                    default -> defaultValue(method.getReturnType());
                });
        builderReference.set(builder);

        CommandManager commandManager = proxy(CommandManager.class, (ignored, method, arguments) -> {
            switch (method.getName()) {
                case "metaBuilder" -> {
                    primaryAlias.set((String) arguments[0]);
                    return builder;
                }
                case "register" -> {
                    registeredMeta.set((CommandMeta) arguments[0]);
                    registeredCommand.set((Command) arguments[1]);
                    return null;
                }
                case "unregister" -> {
                    assertSame(metadata, arguments[0]);
                    unregisterCalls.incrementAndGet();
                    return null;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        PluginManager pluginManager = proxy(PluginManager.class, (ignored, method, arguments) ->
                method.getName().equals("getPlugin")
                        ? Optional.empty()
                        : defaultValue(method.getReturnType()));
        ProxyServer server = proxy(ProxyServer.class, (ignored, method, arguments) ->
                switch (method.getName()) {
                    case "getCommandManager" -> commandManager;
                    case "getPluginManager" -> pluginManager;
                    default -> defaultValue(method.getReturnType());
                });

        ExampleVelocityLifecycle lifecycle = new ExampleVelocityLifecycle(plugin, server);
        lifecycle.start();

        assertEquals("craftrelayexample", primaryAlias.get());
        assertEquals(List.of("crelay"), aliases.get());
        assertSame(plugin, associatedPlugin.get());
        assertSame(metadata, registeredMeta.get());
        assertInstanceOf(RawCommand.class, registeredCommand.get());

        lifecycle.stop();
        lifecycle.stop();
        assertEquals(1, unregisterCalls.get());
    }

    private static <T> T proxy(
            Class<T> type,
            java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
