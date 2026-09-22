package com.uno.dto.realtime;

public record PublicPlayerInfo(
        Long userId,
        String username,
        String avatarDataUrl,
        int handCount,
        Integer seatIndex,
        boolean saidUno,
        boolean currentPlayer,
        boolean rematchReady
) {
}
