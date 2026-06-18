package com.uno.dto.realtime;

import java.util.List;
import java.util.Map;

public record FullRealtimeSnapshot(
        String type,
        Long version,
        Map<String, Object> roomState,
        Map<String, Object> gameState,
        List<Map<String, Object>> handCards
) {
}
