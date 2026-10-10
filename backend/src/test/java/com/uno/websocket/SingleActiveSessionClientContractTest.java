package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleActiveSessionClientContractTest {

    @Test
    void lobbyAndGameSubscribeToReplacementChannelAndDoNotReconnectReplacedSocket() throws IOException {
        String control = readResource("static/js/pages/session-control.js");
        String lobby = readResource("static/js/pages/lobby.js");
        String game = readResource("static/js/pages/game.js");

        assertTrue(control.contains("/user/queue/session"));
        assertTrue(control.contains("SESSION_REPLACED"));
        assertTrue(control.contains("x-session-replaced"));
        assertTrue(control.contains("window.location.replace(\"index.html\")"));
        assertTrue(lobby.contains("sessionControl.subscribe(stompClient.value)"));
        assertTrue(lobby.contains("if (sessionControl.handleSocketClose?.(event)) return;"));
        assertTrue(game.contains("sessionControl.subscribe(stompClient.value)"));
        assertTrue(game.contains("if (sessionControl.handleSocketClose?.(event))"));
    }

    @Test
    void loginPageDisplaysTheExplicitReplacementReason() throws IOException {
        String page = readResource("static/index.html");
        String login = readResource("static/js/pages/login.js");
        String control = readResource("static/js/pages/session-control.js");

        assertTrue(page.contains("js/pages/session-control.js"));
        assertTrue(login.contains("consumeNotice"));
        assertTrue(control.contains("This account was signed in on another device."));
        assertTrue(control.contains("You have been signed out."));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
