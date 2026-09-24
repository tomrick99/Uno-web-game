package com.uno.dto.realtime;

import java.util.List;
import java.util.Map;

public record PublicGamePatch(
        String type,
        Long roomId,
        Long gameId,
        Long version,
        Long timestamp,
        Long actorUserId,
        String actorName,
        Long currentPlayerId,
        String currentPlayerName,
        Integer currentPlayerIndex,
        String currentColor,
        Integer direction,
        Map<String, Object> topCard,
        Map<String, Object> discardTopCard,
        Integer pendingPenalty,
        String pendingDrawType,
        Long lastPenaltyPlayerId,
        Integer drawPileSize,
        String gameStatus,
        String roomStatus,
        Boolean gameTimerEnabled,
        Long timerEndsAtEpochMs,
        Long turnEndsAtEpochMs,
        List<Map<String, Object>> unoWindows,
        String finishReason,
        List<PublicPlayerInfo> players,
        Long winnerId,
        List<Long> rematchReadyPlayerIds,
        String message,
        Boolean resyncRequired
) {
}
