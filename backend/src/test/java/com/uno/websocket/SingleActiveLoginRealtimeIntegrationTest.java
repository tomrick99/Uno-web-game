package com.uno.websocket;

import com.uno.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:uno_single_login_realtime;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class SingleActiveLoginRealtimeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private SimpUserRegistry userRegistry;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sockets = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        sockets.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    void secondLoginPushesImmediateReplacementNoticeToTheOldDevice() throws Exception {
        String username = "c5_" + UUID.randomUUID().toString().replace("-", "");
        String password = "secret123";
        userService.register(username, password);
        DeviceHttpClient deviceA = new DeviceHttpClient(port);
        DeviceHttpClient deviceB = new DeviceHttpClient(port);
        assertEquals(200, deviceA.login(username, password).statusCode());

        String otherUsername = "other_" + UUID.randomUUID().toString().replace("-", "");
        userService.register(otherUsername, password);
        DeviceHttpClient otherDevice = new DeviceHttpClient(port);
        assertEquals(200, otherDevice.login(otherUsername, password).statusCode());

        CountDownLatch noticeReceived = new CountDownLatch(1);
        CountDownLatch otherAccountNotice = new CountDownLatch(1);
        AtomicReference<Map<?, ?>> notice = new AtomicReference<>();
        StompSession deviceASocket = connect(deviceA);
        deviceASocket.subscribe(
                "/user/queue/session-control",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Map.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        notice.set((Map<?, ?>) payload);
                        noticeReceived.countDown();
                    }
                }
        );
        assertTrue(awaitSubscription(username, "/user/queue/session-control"),
                "old device subscription must be active");
        StompSession otherAccountSocket = connect(otherDevice);
        otherAccountSocket.subscribe(
                "/user/queue/session-control",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Map.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        otherAccountNotice.countDown();
                    }
                }
        );
        assertTrue(awaitSubscription(otherUsername, "/user/queue/session-control"));

        long loginStartedAt = System.nanoTime();
        assertEquals(200, deviceB.login(username, password).statusCode());

        assertTrue(noticeReceived.await(1, TimeUnit.SECONDS),
                "old device must receive the replacement event without refresh or another request");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loginStartedAt);
        assertTrue(elapsedMs < 1_000L, "replacement notice took " + elapsedMs + "ms");
        assertNotNull(notice.get());
        assertEquals("SESSION_REPLACED", notice.get().get("type"));
        assertEquals("This account was signed in on another device. You have been signed out.",
                notice.get().get("message"));
        assertTrue(awaitSessionCount(username, 0), "the old WebSocket must be closed promptly");
        assertTrue(otherAccountSocket.isConnected(), "another account's connection must stay open");
        assertEquals(1L, otherAccountNotice.getCount(), "another account must not receive the kick-out");

        HttpResponse<String> oldOperation = deviceA.post("/api/game/999/draw");
        HttpResponse<String> newOperation = deviceB.get("/api/user/me");
        assertEquals(401, oldOperation.statusCode());
        assertEquals(200, newOperation.statusCode());

        StompSession deviceBSocket = connect(deviceB);
        assertTrue(deviceBSocket.isConnected());
        assertTrue(awaitSessionCount(username, 1),
                "the replacement device must be the account's only active WebSocket connection");
    }

    private StompSession connect(DeviceHttpClient device) throws Exception {
        if (stompClient == null) {
            SockJsClient sockJsClient = new SockJsClient(List.of(
                    new WebSocketTransport(new StandardWebSocketClient())
            ));
            stompClient = new WebSocketStompClient(sockJsClient);
            stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        }
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, device.cookieHeader());
        StompSession session = stompClient.connectAsync(
                        "ws://localhost:" + port + "/api/ws",
                        headers,
                        new StompSessionHandlerAdapter() { })
                .get(3, TimeUnit.SECONDS);
        sockets.add(session);
        return session;
    }

    private boolean awaitSubscription(String username, String destination) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var user = userRegistry.getUser(username);
            if (user != null && user.getSessions().stream()
                    .flatMap(session -> session.getSubscriptions().stream())
                    .map(SimpSubscription::getDestination)
                    .anyMatch(destination::equals)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private boolean awaitSessionCount(String username, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            var user = userRegistry.getUser(username);
            int count = user == null ? 0 : user.getSessions().size();
            if (count == expectedCount) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static final class DeviceHttpClient {
        private final int port;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(cookies)
                .build();

        private DeviceHttpClient(int port) {
            this.port = port;
        }

        private HttpResponse<String> login(String username, String password) throws Exception {
            String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
            HttpRequest request = HttpRequest.newBuilder(uri("/api/user/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> get(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        private HttpResponse<String> post(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        private String cookieHeader() {
            return cookies.getCookieStore().getCookies().stream()
                    .map(HttpCookie::toString)
                    .reduce((left, right) -> left + "; " + right)
                    .orElseThrow();
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
