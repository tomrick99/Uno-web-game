package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleActiveDeviceClientContractTest {

    @Test
    void lobbyAndGameClientsShowReplacementReasonAndStopReconnects() throws IOException {
        for (String resource : List.of("static/js/pages/lobby.js", "static/js/pages/game.js")) {
            String script = readResource(resource);
            assertTrue(script.contains("/user/queue/session-status"));
            assertTrue(script.contains("SESSION_REPLACED"));
            assertTrue(script.contains("shouldReconnect = false"));
            assertTrue(script.contains("window.alert(message)"));
            assertTrue(script.contains("window.location.replace(\"index.html\")"));
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
