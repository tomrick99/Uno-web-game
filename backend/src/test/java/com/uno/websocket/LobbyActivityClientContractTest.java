package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyActivityClientContractTest {

    @Test
    void lobbyHandlesActivityOnTheSameTopicAsWorkingRoomEvents() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/js/pages/lobby.js"));

        assertTrue(script.contains("currentUserId.value = currentUser.id || null"));
        assertTrue(script.contains("stompClient.value.subscribe(\"/topic/lobby\""));
        assertTrue(script.contains("payload.type === \"LOBBY_ACTIVITY\""));
        assertTrue(script.contains("realtimeUtils.shouldShowLobbyActivity(payload, currentUserId.value)"));
        assertFalse(script.contains("/topic/lobby/activity/"));
        assertFalse(script.contains("/user/queue/lobby/activity"));
    }
}
