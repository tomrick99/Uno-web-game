package com.uno.websocket;

import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:uno_lobby_activity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class LobbyActivityWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GamePlayerRepository gamePlayerRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        gamePlayerRepository.deleteAll();
        gameRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();

        SockJsClient sockJsClient = new SockJsClient(List.of(
                new WebSocketTransport(new StandardWebSocketClient())));
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : sessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        stompClient.stop();
    }

    @Test
    void roomCreatedReachesOnlyOtherRoomlessUserAndDoesNotDuplicateJoin() throws Exception {
        LoggedInUser alice = registerAndLogin("alice");
        LoggedInUser bob = registerAndLogin("bob");
        LoggedInUser carol = registerAndLogin("carol");
        createRoom(carol, 4);

        BlockingQueue<Map<String, Object>> aliceMessages = subscribe(alice);
        BlockingQueue<Map<String, Object>> bobMessages = subscribe(bob);
        BlockingQueue<Map<String, Object>> carolMessages = subscribe(carol);

        long roomId = createRoom(alice, 4);

        Map<String, Object> notification = bobMessages.poll(3, TimeUnit.SECONDS);
        assertNotNull(notification);
        assertEquals("LOBBY_ACTIVITY", notification.get("type"));
        assertEquals("ROOM_CREATED", notification.get("event"));
        assertEquals(roomId, ((Number) notification.get("roomId")).longValue());
        assertEquals("alice", actorUsername(notification));
        assertNull(bobMessages.poll(300, TimeUnit.MILLISECONDS), "creator auto-join must not create a second notification");
        assertNull(aliceMessages.poll(300, TimeUnit.MILLISECONDS), "creator must not receive their own activity");
        assertNull(carolMessages.poll(300, TimeUnit.MILLISECONDS), "room participants must be excluded");
    }

    @Test
    void playerJoinedReachesOnlyOtherRoomlessUser() throws Exception {
        LoggedInUser alice = registerAndLogin("alice");
        LoggedInUser bob = registerAndLogin("bob");
        LoggedInUser carol = registerAndLogin("carol");
        LoggedInUser dave = registerAndLogin("dave");
        long targetRoomId = createRoom(alice, 4);
        createRoom(carol, 4);

        BlockingQueue<Map<String, Object>> bobMessages = subscribe(bob);
        BlockingQueue<Map<String, Object>> carolMessages = subscribe(carol);
        BlockingQueue<Map<String, Object>> daveMessages = subscribe(dave);

        joinRoom(dave, targetRoomId);

        Map<String, Object> notification = bobMessages.poll(3, TimeUnit.SECONDS);
        assertNotNull(notification);
        assertEquals("PLAYER_JOINED", notification.get("event"));
        assertEquals(targetRoomId, ((Number) notification.get("roomId")).longValue());
        assertEquals("dave", actorUsername(notification));
        assertNull(carolMessages.poll(300, TimeUnit.MILLISECONDS), "room participants must be excluded");
        assertNull(daveMessages.poll(300, TimeUnit.MILLISECONDS), "joining player must not receive their own activity");
    }

    @Test
    void playerJoinedActivityIsPublishedAfterAutomaticGameStartUpdate() throws Exception {
        LoggedInUser alice = registerAndLogin("alice");
        LoggedInUser bob = registerAndLogin("bob");
        LoggedInUser dave = registerAndLogin("dave");
        long targetRoomId = createRoom(alice, 2);

        BlockingQueue<Map<String, Object>> bobMessages = subscribeAll(bob);
        joinRoom(dave, targetRoomId);

        List<Map<String, Object>> received = new ArrayList<>();
        Map<String, Object> message;
        do {
            message = bobMessages.poll(3, TimeUnit.SECONDS);
            assertNotNull(message);
            received.add(message);
        } while (!"LOBBY_ACTIVITY".equals(message.get("type")));

        assertTrue(received.stream().anyMatch(event ->
                "LOBBY_EVENT".equals(event.get("type"))
                        && "ROOM_UPDATED".equals(event.get("event"))
                        && "Game started".equals(event.get("message"))));
        assertEquals("PLAYER_JOINED", message.get("event"));
        assertEquals("dave", actorUsername(message));
        assertNull(bobMessages.poll(300, TimeUnit.MILLISECONDS),
                "join activity must remain the last lobby event so it is not immediately overwritten");
    }

    private LoggedInUser registerAndLogin(String username) {
        Map<String, String> credentials = Map.of("username", username, "password", "password123");
        restTemplate.postForEntity(httpUrl("/api/user/register"), credentials, Map.class);
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                httpUrl("/api/user/login"), credentials, Map.class);
        assertEquals(200, ((Number) loginResponse.getBody().get("code")).intValue());
        String setCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        Map<?, ?> data = (Map<?, ?>) loginResponse.getBody().get("data");
        return new LoggedInUser(setCookie.split(";", 2)[0], ((Number) data.get("id")).longValue());
    }

    private long createRoom(LoggedInUser user, int maxPlayers) {
        Map<String, Object> request = Map.of(
                "maxPlayers", maxPlayers,
                "totalRounds", 8,
                "roundTimeLimitMinutes", 10,
                "gameMode", "CLASSIC");
        ResponseEntity<Map> response = restTemplate.postForEntity(
                httpUrl("/api/room/create"), new HttpEntity<>(request, cookieHeaders(user.cookie())), Map.class);
        assertEquals(200, ((Number) response.getBody().get("code")).intValue());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return ((Number) data.get("roomId")).longValue();
    }

    private void joinRoom(LoggedInUser user, long roomId) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                httpUrl("/api/game/" + roomId + "/join"),
                new HttpEntity<>(null, cookieHeaders(user.cookie())),
                Map.class);
        assertEquals(200, ((Number) response.getBody().get("code")).intValue());
    }

    private BlockingQueue<Map<String, Object>> subscribe(LoggedInUser user) throws Exception {
        return subscribe(user, true);
    }

    private BlockingQueue<Map<String, Object>> subscribeAll(LoggedInUser user) throws Exception {
        return subscribe(user, false);
    }

    private BlockingQueue<Map<String, Object>> subscribe(LoggedInUser user, boolean activitiesOnly) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, user.cookie());
        StompSession session = stompClient.connectAsync(
                        "ws://localhost:" + port + "/api/ws",
                        handshakeHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);

        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        session.subscribe("/topic/lobby", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                Map<String, Object> event = (Map<String, Object>) payload;
                if (activitiesOnly && !"LOBBY_ACTIVITY".equals(event.get("type"))) {
                    return;
                }
                List<?> excludedUserIds = (List<?>) event.getOrDefault("excludedUserIds", List.of());
                boolean excluded = excludedUserIds.stream()
                        .map(value -> ((Number) value).longValue())
                        .anyMatch(user.id()::equals);
                if (!excluded) {
                    messages.add(event);
                }
            }
        });

        assertTrue(waitUntilSubscribed(session), "lobby activity subscription was not registered");
        return messages;
    }

    private boolean waitUntilSubscribed(StompSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.isConnected()) {
                Thread.sleep(25);
                return true;
            }
        }
        return false;
    }

    private String actorUsername(Map<String, Object> notification) {
        Map<?, ?> actor = (Map<?, ?>) notification.get("actor");
        return String.valueOf(actor.get("username"));
    }

    private HttpHeaders cookieHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    private String httpUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private record LoggedInUser(String cookie, Long id) {}
}
