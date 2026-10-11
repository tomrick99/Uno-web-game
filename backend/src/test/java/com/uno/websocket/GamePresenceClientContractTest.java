package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamePresenceClientContractTest {

    @Test
    void servedGameClientIdentifiesItsStompConnection() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/static/js/pages/game.js")) {
            assertTrue(input != null, "served game.js must exist");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("connectHeaders"));
            assertTrue(source.contains("\"x-uno-client\": \"game\""));
            assertTrue(source.contains("\"x-uno-room-id\""));
            assertTrue(source.contains("await joinAndLoad();"));
            assertTrue(source.contains("deadlineEpochMs"));
            assertFalse(source.contains("disconnectWaitReceivedAt"));
        }
    }
}
