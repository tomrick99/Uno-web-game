package com.uno.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GamePageReconnectContractTest {

    @Test
    void websocketReconnectAutomaticallyRestoresAndRendersThePlayerSnapshot() throws IOException {
        String script = new ClassPathResource("static/js/pages/game.js")
                .getContentAsString(StandardCharsets.UTF_8);

        int reconnectRequest = script.indexOf("axios.post(`${apiBase}/game/room/${roomId.value}/reconnect`)");
        int renderRestoredSnapshot = script.indexOf("renderSnapshot(snapshot);", reconnectRequest);
        int subscribeRoom = script.indexOf("subscribeRoomTopic();");
        int subscribePrivateGameChannels = script.indexOf("subscribeGameChannels(gameId.value);", subscribeRoom);
        int invokeAutomaticRestore = script.indexOf("await restoreAfterReconnect();", subscribePrivateGameChannels);

        assertTrue(reconnectRequest >= 0, "The reconnect endpoint must be called automatically");
        assertTrue(renderRestoredSnapshot > reconnectRequest,
                "The reconnect response must be rendered without a manual page refresh");
        assertTrue(subscribeRoom >= 0 && subscribePrivateGameChannels > subscribeRoom,
                "Realtime subscriptions must be restored before state recovery");
        assertTrue(invokeAutomaticRestore > subscribePrivateGameChannels,
                "Successful WebSocket reconnection must trigger player state recovery");
    }
}
