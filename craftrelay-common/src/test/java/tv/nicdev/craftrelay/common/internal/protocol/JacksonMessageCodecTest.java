package tv.nicdev.craftrelay.common.internal.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.InvalidMessageException;
import tv.nicdev.craftrelay.api.exception.ProtocolException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.InstanceHeartbeatMessage;
import tv.nicdev.craftrelay.api.message.InstanceStartedMessage;
import tv.nicdev.craftrelay.api.message.InstanceStoppedMessage;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.message.PlayerConnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerDisconnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.message.PlayerServerSwitchMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.api.target.NetworkTargets;

class JacksonMessageCodecTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final UUID MESSAGE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CORRELATION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAYER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SESSION_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void everyStandardMessageRoundTrips() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);

        for (NetworkMessage message : standardMessages()) {
            DecodedMessage decoded =
                    codec.decode(
                            codec.encode(
                                    "proxy-eu-1",
                                    NetworkTargets.allServers(),
                                    message,
                                    Optional.of(CORRELATION_ID)));

            assertEquals(message, decoded.message());
            assertEquals(MESSAGE_ID, decoded.envelope().messageId());
            assertEquals(NOW, decoded.envelope().createdAt());
            assertEquals(Optional.of(CORRELATION_ID), decoded.envelope().correlationId());
            assertEquals("proxy-eu-1", decoded.envelope().sourceInstance());
        }
    }

    @Test
    void everyTargetUsesAnExplicitRoundTripFormat() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        List<NetworkTarget> targets =
                List.of(
                        NetworkTargets.allInstances(),
                        NetworkTargets.allProxies(),
                        NetworkTargets.allServers(),
                        NetworkTargets.instance("server-1"),
                        NetworkTargets.group("eu"));

        for (NetworkTarget target : targets) {
            byte[] encoded =
                    codec.encode(
                            "proxy-1",
                            target,
                            new GlobalBroadcastMessage("hello"),
                            Optional.empty());
            assertEquals(target, codec.decode(encoded).envelope().target());
        }
    }

    @Test
    void deterministicInputsProduceDeterministicJson() {
        JacksonMessageCodec first = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        JacksonMessageCodec second = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        NetworkMessage message = new GlobalBroadcastMessage("hello");

        assertArrayEquals(
                first.encode("proxy-1", NetworkTargets.allServers(), message, Optional.empty()),
                second.encode("proxy-1", NetworkTargets.allServers(), message, Optional.empty()));
    }

    @Test
    void envelopeDefensivelyCopiesItsJsonPayload() {
        ObjectNode original = JsonMapper.builder().build().createObjectNode();
        original.put("content", "initial");
        MessageEnvelope envelope =
                new MessageEnvelope(
                        MESSAGE_ID,
                        1,
                        "craftrelay:global_broadcast",
                        "proxy-1",
                        NetworkTargets.allServers(),
                        NOW,
                        Optional.empty(),
                        original);

        original.put("content", "changed");
        ((ObjectNode) envelope.payload()).put("content", "also-changed");

        assertEquals("initial", envelope.payload().get("content").stringValue());
    }

    @Test
    void exactMaximumSizeIsAllowedAndOneByteLessIsRejected() {
        JacksonMessageCodec unlimited = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        NetworkMessage message = new GlobalBroadcastMessage("boundary");
        byte[] encoded =
                unlimited.encode(
                        "proxy-1", NetworkTargets.allServers(), message, Optional.empty());

        JacksonMessageCodec exact = codec(encoded.length);
        assertEquals(
                message,
                exact.decode(
                                exact.encode(
                                        "proxy-1",
                                        NetworkTargets.allServers(),
                                        message,
                                        Optional.empty()))
                        .message());
        JacksonMessageCodec tooSmall = codec(encoded.length - 1);
        assertThrows(
                ProtocolException.class,
                () ->
                        tooSmall.encode(
                                "proxy-1",
                                NetworkTargets.allServers(),
                                message,
                                Optional.empty()));
        assertThrows(ProtocolException.class, () -> tooSmall.decode(encoded));
    }

    @Test
    void malformedAndStructurallyInvalidEnvelopesAreRejected() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        byte[] valid =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allServers(),
                        new GlobalBroadcastMessage("hello"),
                        Optional.empty());
        String json = new String(valid, StandardCharsets.UTF_8);

        assertThrows(ProtocolException.class, () -> codec.decode(new byte[0]));
        assertThrows(
                ProtocolException.class,
                () -> codec.decode("{broken".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                ProtocolException.class,
                () ->
                        codec.decode(
                                json.replace("\"protocolVersion\":1", "\"protocolVersion\":2")
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                ProtocolException.class,
                () ->
                        codec.decode(
                                json.replace("\"ALL_SERVERS\"", "\"SOMEWHERE\"")
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                ProtocolException.class,
                () ->
                        codec.decode(
                                json.replaceFirst("\"messageId\":", "\"unexpected\":true,\"messageId\":")
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingCorrelationIdIsDecodedAsEmpty() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        byte[] encoded =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allServers(),
                        new GlobalBroadcastMessage("hello"),
                        Optional.empty());
        String withoutCorrelationId =
                new String(encoded, StandardCharsets.UTF_8)
                        .replace(",\"correlationId\":null", "");

        DecodedMessage decoded =
                codec.decode(withoutCorrelationId.getBytes(StandardCharsets.UTF_8));

        assertEquals(Optional.empty(), decoded.envelope().correlationId());
        assertEquals(new GlobalBroadcastMessage("hello"), decoded.message());
    }

    @Test
    void unknownMessageTypesNeverTriggerDynamicClassLoading() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        byte[] valid =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allServers(),
                        new GlobalBroadcastMessage("hello"),
                        Optional.empty());
        String manipulated =
                new String(valid, StandardCharsets.UTF_8)
                        .replace("craftrelay:global_broadcast", "java.lang.Runtime");

        assertThrows(
                InvalidMessageException.class,
                () -> codec.decode(manipulated.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void payloadTypeMetadataIsTreatedAsOrdinaryInvalidData() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        byte[] valid =
                codec.encode(
                        "proxy-1",
                        NetworkTargets.allServers(),
                        new GlobalBroadcastMessage("hello"),
                        Optional.empty());
        String manipulated =
                new String(valid, StandardCharsets.UTF_8)
                        .replace(
                                "\"payload\":{\"content\":\"hello\"}",
                                "\"payload\":{\"@class\":\"java.lang.Runtime\",\"content\":\"hello\"}");

        assertThrows(
                ProtocolException.class,
                () -> codec.decode(manipulated.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unregisteredRuntimeMessagesAreInvalid() {
        JacksonMessageCodec codec = codec(JacksonMessageCodec.DEFAULT_MAXIMUM_MESSAGE_SIZE);
        NetworkMessage custom = new NetworkMessage() {
        };

        assertThrows(
                InvalidMessageException.class,
                () ->
                        codec.encode(
                                "proxy-1",
                                NetworkTargets.allServers(),
                                custom,
                                Optional.empty()));
    }

    private static JacksonMessageCodec codec(int maximumSize) {
        return new JacksonMessageCodec(
                MessageRegistry.withStandardMessages(),
                JsonMapper.builder().build(),
                maximumSize,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> MESSAGE_ID);
    }

    private static List<NetworkMessage> standardMessages() {
        NetworkInstance instance =
                new NetworkInstance(
                        "proxy-eu-1",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu"),
                        NOW.minusSeconds(60),
                        NOW,
                        4);
        NetworkPlayer player =
                new NetworkPlayer(
                        PLAYER_ID,
                        "NicDev",
                        "proxy-eu-1",
                        Optional.of("lobby-1"),
                        SESSION_ID,
                        NOW.minusSeconds(30),
                        NOW);
        return List.of(
                new InstanceStartedMessage(instance),
                new InstanceStoppedMessage("proxy-eu-1", NOW),
                new InstanceHeartbeatMessage(instance),
                new PlayerConnectedMessage(player),
                new PlayerDisconnectedMessage(PLAYER_ID, SESSION_ID, "proxy-eu-1", NOW),
                new PlayerServerSwitchMessage(
                        PLAYER_ID,
                        SESSION_ID,
                        "proxy-eu-1",
                        Optional.of("lobby-1"),
                        "survival-1",
                        NOW),
                new PlayerLocationRequest(PLAYER_ID),
                new PlayerLocationResponse(PLAYER_ID, Optional.of(player)),
                new PlayerConnectRequest(PLAYER_ID, "survival-1"),
                new GlobalBroadcastMessage("hello network"));
    }
}
