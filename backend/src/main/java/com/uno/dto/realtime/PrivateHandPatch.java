package com.uno.dto.realtime;

import java.util.List;
import java.util.Map;

public record PrivateHandPatch(
        String type,
        Long roomId,
        Long gameId,
        Long version,
        Long timestamp,
        Long userId,
        String patchId,
        List<Map<String, Object>> handCards,
        Boolean canPlay,
        Boolean mustDraw,
        Integer pendingPenalty
) {
}
