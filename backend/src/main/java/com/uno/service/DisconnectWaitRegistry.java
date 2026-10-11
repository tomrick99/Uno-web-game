package com.uno.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DisconnectWaitRegistry {

    public static final long GRACE_PERIOD_MILLIS = 60_000L;

    public record WaitState(Long roomId,
                            Long gameId,
                            Long disconnectedPlayerId,
                            String disconnectedPlayerName,
                            long startedAtEpochMs,
                            long deadlineEpochMs,
                            Set<Long> approvedPlayerIds) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<Long, WaitState> waitsByRoom = new ConcurrentHashMap<>();

    public DisconnectWaitRegistry() {
        this(Clock.systemUTC());
    }

    public DisconnectWaitRegistry(Clock clock) {
        this.clock = clock;
    }

    public WaitState start(Long roomId, Long gameId, Long playerId, String playerName) {
        long now = clock.millis();
        WaitState candidate = new WaitState(
                roomId,
                gameId,
                playerId,
                playerName,
                now,
                now + GRACE_PERIOD_MILLIS,
                ConcurrentHashMap.newKeySet()
        );
        return waitsByRoom.putIfAbsent(roomId, candidate) == null ? candidate : null;
    }

    public WaitState get(Long roomId) {
        return waitsByRoom.get(roomId);
    }

    public WaitState cancel(Long roomId, Long playerId) {
        WaitState current = waitsByRoom.get(roomId);
        if (current == null || !current.disconnectedPlayerId().equals(playerId)) {
            return null;
        }
        return waitsByRoom.remove(roomId, current) ? current : null;
    }

    public WaitState remove(Long roomId) {
        return waitsByRoom.remove(roomId);
    }

    public boolean approve(Long roomId, Long playerId) {
        WaitState current = waitsByRoom.get(roomId);
        return current != null && current.approvedPlayerIds().add(playerId);
    }

    public boolean isWaiting(Long roomId) {
        return waitsByRoom.containsKey(roomId);
    }

    public int remainingSeconds(WaitState state) {
        if (state == null) {
            return 0;
        }
        long remainingMillis = Math.max(0L, state.deadlineEpochMs() - clock.millis());
        return (int) Math.ceil(remainingMillis / 1000.0);
    }

    public List<WaitState> expiredWaits() {
        long now = clock.millis();
        return waitsByRoom.values().stream()
                .filter(state -> state.deadlineEpochMs() <= now)
                .toList();
    }

    public Map<String, Object> toPublicState(WaitState state, List<Long> requiredPlayerIds) {
        if (state == null) {
            return null;
        }
        List<Long> approved = new ArrayList<>(state.approvedPlayerIds());
        approved.sort(Long::compareTo);
        List<Long> required = new ArrayList<>(requiredPlayerIds == null ? List.of() : requiredPlayerIds);
        required.sort(Long::compareTo);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", true);
        result.put("disconnectedPlayerId", state.disconnectedPlayerId());
        result.put("disconnectedPlayerName", state.disconnectedPlayerName());
        result.put("startedAtEpochMs", state.startedAtEpochMs());
        result.put("deadlineEpochMs", state.deadlineEpochMs());
        result.put("gracePeriodSeconds", GRACE_PERIOD_MILLIS / 1000L);
        result.put("remainingSeconds", remainingSeconds(state));
        result.put("approvedPlayerIds", approved);
        result.put("approvalCount", approved.stream().filter(required::contains).count());
        result.put("requiredPlayerIds", required);
        result.put("requiredCount", required.size());
        return result;
    }
}
