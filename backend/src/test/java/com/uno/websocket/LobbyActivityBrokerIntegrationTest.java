package com.uno.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lobby_activity_broker;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class LobbyActivityBrokerIntegrationTest {

    private static final String LOBBY_TOPIC = "/topic/lobby";

    @Autowired
    private GameWebSocketService webSocketService;

    @Autowired
    @Qualifier("clientInboundChannel")
    private MessageChannel clientInboundChannel;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private AbstractMessageChannel clientOutboundChannel;

    @Autowired
    private SimpleBrokerMessageHandler brokerMessageHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BlockingQueue<Message<?>> lobbyMessages = new LinkedBlockingQueue<>();
    private final BlockingQueue<Message<?>> controlMessages = new LinkedBlockingQueue<>();
    private ChannelInterceptor outboundInterceptor;

    @BeforeEach
    void subscribeLobbyClient() throws Exception {
        outboundInterceptor = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
                if (accessor.getMessageType() == SimpMessageType.MESSAGE
                        && LOBBY_TOPIC.equals(accessor.getDestination())) {
                    lobbyMessages.add(message);
                } else {
                    controlMessages.add(message);
                }
                return message;
            }
        };
        clientOutboundChannel.addInterceptor(outboundInterceptor);

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("lobby-activity-test-session");
        connect.setAcceptVersion("1.2");
        connect.setHeartbeat(0, 0);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));
        takeControlMessage(SimpMessageType.CONNECT_ACK);

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("lobby-activity-test-session");
        subscribe.setSubscriptionId("lobby-activity-test-subscription");
        subscribe.setDestination(LOBBY_TOPIC);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
        waitForLobbySubscription();
    }

    @AfterEach
    void removeInterceptor() {
        StompHeaderAccessor disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("lobby-activity-test-session");
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], disconnect.getMessageHeaders()));
        clientOutboundChannel.removeInterceptor(outboundInterceptor);
    }

    @Test
    void createAndJoinActivitiesTraverseTheRealLobbyBrokerSubscription() throws Exception {
        Map<String, Object> createdState = Map.of(
                "roomId", 10L,
                "roomCode", "ROOM-X",
                "players", List.of(Map.of("userId", 2L, "username", "B")));
        webSocketService.broadcastLobbyActivity(createdState, "ROOM_CREATED", 2L, "B");

        Map<String, Object> created = takeActivity("ROOM_CREATED");
        assertEquals(10, ((Number) created.get("roomId")).intValue());
        assertEquals("ROOM-X", created.get("roomCode"));
        assertEquals(Map.of("userId", 2, "username", "B"), created.get("actor"));

        Map<String, Object> joinedState = Map.of(
                "roomId", 10L,
                "roomCode", "ROOM-X",
                "players", List.of(
                        Map.of("userId", 2L, "username", "B"),
                        Map.of("userId", 3L, "username", "C")));
        webSocketService.broadcastLobbyActivity(joinedState, "PLAYER_JOINED", 3L, "C");

        Map<String, Object> joined = takeActivity("PLAYER_JOINED");
        assertEquals(10, ((Number) joined.get("roomId")).intValue());
        assertEquals(Map.of("userId", 3, "username", "C"), joined.get("actor"));
    }

    private Map<String, Object> takeActivity(String expectedEvent) throws Exception {
        Message<?> message = lobbyMessages.poll(3, TimeUnit.SECONDS);
        assertNotNull(message, "No message traversed /topic/lobby for " + expectedEvent);
        byte[] body = (byte[]) message.getPayload();
        Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
        assertEquals("LOBBY_ACTIVITY", payload.get("type"));
        assertEquals(expectedEvent, payload.get("event"));
        return payload;
    }

    private Message<?> takeControlMessage(SimpMessageType expectedType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Message<?> message = controlMessages.poll(100, TimeUnit.MILLISECONDS);
            if (message != null
                    && SimpMessageHeaderAccessor.wrap(message).getMessageType() == expectedType) {
                return message;
            }
        }
        throw new AssertionError("No outbound STOMP " + expectedType + " frame was observed");
    }

    private void waitForLobbySubscription() throws Exception {
        SimpMessageHeaderAccessor lookup = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        lookup.setDestination(LOBBY_TOPIC);
        Message<?> lookupMessage = MessageBuilder.createMessage(new byte[0], lookup.getMessageHeaders());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            MultiValueMap<String, String> subscriptions =
                    brokerMessageHandler.getSubscriptionRegistry().findSubscriptions(lookupMessage);
            if (subscriptions.containsKey("lobby-activity-test-session")) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The broker did not register the /topic/lobby subscription");
    }
}
