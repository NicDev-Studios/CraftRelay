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
package tv.nicdev.craftrelay.common.internal.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.InvalidMessageException;
import tv.nicdev.craftrelay.api.exception.ProtocolException;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.target.NetworkTargets;

class CustomMessageCodecTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final UUID MESSAGE_ID =
            UUID.fromString("5b4fc02c-79ef-4d0a-90bc-3d83945bcfc7");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void roundTripsExplicitJsonWithPayloadVersion() {
        JacksonMessageCodec codec = codec();
        MessageType<CustomMessage> type =
                MessageType.of("example", "notice", 2, CustomMessage.class);
        codec.register(type, customCodec());

        byte[] encoded =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allServers(),
                        new CustomMessage("hello"),
                        Optional.empty());
        String json = new String(encoded, StandardCharsets.UTF_8);

        assertEquals(true, json.contains("\"type\":\"example:notice\""));
        assertEquals(true, json.contains("\"payloadVersion\":2"));
        assertEquals(new CustomMessage("hello"), codec.decode(encoded).message());
    }

    @Test
    void prepareDoesNotInvokeCustomDecoder() {
        JacksonMessageCodec codec = codec();
        AtomicInteger decodes = new AtomicInteger();
        MessagePayloadCodec<CustomMessage> payloadCodec =
                new MessagePayloadCodec<>() {
                    @Override
                    public byte[] encode(CustomMessage message) {
                        return "{\"value\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
                    }

                    @Override
                    public CustomMessage decode(byte[] payload) {
                        decodes.incrementAndGet();
                        return new CustomMessage("hello");
                    }
                };
        codec.register(
                MessageType.of("example", "notice", 1, CustomMessage.class),
                payloadCodec);
        byte[] encoded =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allInstances(),
                        new CustomMessage("hello"),
                        Optional.empty());

        PreparedMessage prepared = codec.prepare(encoded);
        assertEquals(0, decodes.get());
        codec.decode(prepared);
        assertEquals(1, decodes.get());
    }

    @Test
    void rejectsEnvelopeWithoutPayloadVersion() {
        JacksonMessageCodec codec = codec();
        byte[] current =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allInstances(),
                        new tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage("legacy"),
                        Optional.empty());
        String legacy =
                new String(current, StandardCharsets.UTF_8)
                        .replace("\"payloadVersion\":1,", "");

        assertThrows(
                ProtocolException.class,
                () -> codec.decode(legacy.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnknownPayloadVersionWithoutLoadingClasses() {
        JacksonMessageCodec codec = codec();
        codec.register(
                MessageType.of("example", "notice", 1, CustomMessage.class),
                customCodec());
        String encoded =
                new String(
                                codec.encode(
                                        "proxy-1",
                                        NetworkTargets.allInstances(),
                                        new CustomMessage("hello"),
                                        Optional.empty()),
                                StandardCharsets.UTF_8)
                        .replace("\"payloadVersion\":1", "\"payloadVersion\":99");

        assertThrows(
                InvalidMessageException.class,
                () -> codec.decode(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidCustomEncoderResults() {
        for (byte[] invalid :
                new byte[][] {
                    new byte[0],
                    "[]".getBytes(StandardCharsets.UTF_8),
                    "{\"value\":".getBytes(StandardCharsets.UTF_8),
                    "{} {}".getBytes(StandardCharsets.UTF_8)
                }) {
            JacksonMessageCodec codec = codec();
            codec.register(
                    MessageType.of("example", "notice", 1, CustomMessage.class),
                    constantCodec(invalid));
            assertThrows(
                    InvalidMessageException.class,
                    () ->
                            codec.encode(
                                    "proxy-1",
                                    NetworkTargets.allInstances(),
                                    new CustomMessage("hello"),
                                    Optional.empty()));
        }
    }

    @Test
    void rejectsNullAndWrongCustomDecoderResults() {
        JacksonMessageCodec nullCodec = codec();
        nullCodec.register(
                MessageType.of("example", "notice", 1, CustomMessage.class),
                constantCodec("{}".getBytes(StandardCharsets.UTF_8)));
        byte[] encoded =
                nullCodec.encode(
                        "proxy-1",
                        NetworkTargets.allInstances(),
                        new CustomMessage("hello"),
                        Optional.empty());
        assertThrows(ProtocolException.class, () -> nullCodec.decode(encoded));

        JacksonMessageCodec wrongCodec = codec();
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessagePayloadCodec<CustomMessage> unsafe =
                (MessagePayloadCodec)
                        new MessagePayloadCodec<OtherMessage>() {
                            @Override
                            public byte[] encode(OtherMessage message) {
                                return "{}".getBytes(StandardCharsets.UTF_8);
                            }

                            @Override
                            public OtherMessage decode(byte[] payload) {
                                return new OtherMessage();
                            }
                        };
        wrongCodec.register(
                MessageType.of("example", "wrong", 1, CustomMessage.class),
                unsafe);
        byte[] wrongEncoded =
                replacePayloadType(
                        encoded, "example:notice", "example:wrong");
        assertThrows(ProtocolException.class, () -> wrongCodec.decode(wrongEncoded));
    }

    private static JacksonMessageCodec codec() {
        return new JacksonMessageCodec(
                MessageRegistry.withStandardMessages(),
                JSON,
                JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> MESSAGE_ID);
    }

    private static MessagePayloadCodec<CustomMessage> customCodec() {
        return new MessagePayloadCodec<>() {
            @Override
            public byte[] encode(CustomMessage message) {
                try {
                    return JSON.writeValueAsBytes(java.util.Map.of("value", message.value()));
                } catch (RuntimeException exception) {
                    throw exception;
                }
            }

            @Override
            public CustomMessage decode(byte[] payload) {
                try {
                    return new CustomMessage(
                            JSON.readTree(payload).get("value").stringValue());
                } catch (RuntimeException exception) {
                    throw exception;
                }
            }
        };
    }

    private static MessagePayloadCodec<CustomMessage> constantCodec(byte[] encoded) {
        return new MessagePayloadCodec<>() {
            @Override
            public byte[] encode(CustomMessage message) {
                return encoded;
            }

            @Override
            public CustomMessage decode(byte[] payload) {
                return null;
            }
        };
    }

    private static byte[] replacePayloadType(byte[] encoded, String from, String to) {
        return new String(encoded, StandardCharsets.UTF_8)
                .replace(from, to)
                .getBytes(StandardCharsets.UTF_8);
    }

    private record CustomMessage(String value) implements NetworkMessage {
    }

    private record OtherMessage() implements NetworkMessage {
    }
}
