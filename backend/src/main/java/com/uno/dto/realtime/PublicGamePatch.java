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
        List<PublicPlayerInfo> players,
        Long winnerId,
        List<Long> rematchReadyPlayerIds,
        Boolean continuationPending,
        List<Long> continuationReadyPlayerIds,
        String message,
        Boolean resyncRequired
) {
}
