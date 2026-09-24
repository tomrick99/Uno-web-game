package com.uno.dto.realtime;

public record PublicUnoWindow(
        Long eventId,
        Long targetPlayerId,
        String targetPlayerName,
        Long endsAtEpochMs
) {
}
