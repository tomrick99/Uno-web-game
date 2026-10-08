package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClientReconnectContractTest {

    @Test
    void websocketReconnectRejoinsBeforeApplyingAuthoritativeSnapshot() throws IOException {
        String script;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("static/js/pages/game.js")) {
            assertNotNull(input, "served game client script must exist");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int connectedHandlerStart = script.indexOf("const handleConnected = async () => {");
        int connectErrorHandlerStart = script.indexOf("const handleConnectError =", connectedHandlerStart);
        assertTrue(connectedHandlerStart >= 0 && connectErrorHandlerStart > connectedHandlerStart,
                "websocket connected handler must be present");

        String connectedHandler = script.substring(connectedHandlerStart, connectErrorHandlerStart);
        assertTrue(connectedHandler.contains("if (resyncOnConnect)"));
        assertTrue(connectedHandler.contains("await joinAndLoad();"),
                "reconnecting clients must use the idempotent join path and apply its post-reconnect snapshot");
        assertFalse(connectedHandler.contains("await refreshFromServer"),
                "a read-only snapshot can race the subscription-driven reconnect restoration");
    }
}
