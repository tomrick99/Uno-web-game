package com.uno.websocket;

import com.uno.session.ActiveLoginService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionReplacementClientContractTest {

    @Test
    void lobbyAndGameClientsHandleAReplacementNoticeAndShowItsReasonOnLogin() throws IOException {
        String lobby = readResource("static/js/pages/lobby.js");
        String game = readResource("static/js/pages/game.js");
        String login = readResource("static/js/pages/login.js");

        for (String authenticatedClient : new String[] { lobby, game }) {
            assertTrue(authenticatedClient.contains("/user/queue/session-control"));
            assertTrue(authenticatedClient.contains("SESSION_REPLACED"));
            assertTrue(authenticatedClient.contains("shouldReconnect = false"));
            assertTrue(authenticatedClient.contains("sessionStorage.setItem(\n                \"loginNotice\""));
            assertTrue(authenticatedClient.contains(ActiveLoginService.REPLACED_MESSAGE));
        }
        assertTrue(login.contains("sessionStorage.getItem(\"loginNotice\")"));
        assertTrue(login.contains("sessionStorage.removeItem(\"loginNotice\")"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
