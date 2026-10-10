package com.uno.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class DisconnectWaitingRegistry {

    static final class Entry {
        final Long gameId;
        final Long roomId;
        final Long disconnectedUserId;
        final String disconnectedUsername;
        final Instant deadline;
        final Set<Long> approvedUserIds = new LinkedHashSet<>();
        final Set<Long> requiredUserIds = new LinkedHashSet<>();
        ScheduledFuture<?> timeoutTask;

        private Entry(Long gameId,
                      Long roomId,
                      Long disconnectedUserId,
                      String disconnectedUsername,
                      Instant deadline) {
            this.gameId = gameId;
            this.roomId = roomId;
            this.disconnectedUserId = disconnectedUserId;
            this.disconnectedUsername = disconnectedUsername;
            this.deadline = deadline;
        }
    }

    private final Map<Long, Entry> waitingByGameId = new ConcurrentHashMap<>();

    synchronized Entry begin(Long gameId,
                             Long roomId,
                             Long disconnectedUserId,
                             String disconnectedUsername,
                             Instant deadline) {
        Entry existing = waitingByGameId.get(gameId);
        if (existing != null) {
            return existing;
        }
        Entry entry = new Entry(gameId, roomId, disconnectedUserId, disconnectedUsername, deadline);
        waitingByGameId.put(gameId, entry);
        return entry;
    }

    synchronized Entry get(Long gameId) {
        return waitingByGameId.get(gameId);
    }

    synchronized boolean isWaiting(Long gameId) {
        return gameId != null && waitingByGameId.containsKey(gameId);
    }

    synchronized void setTimeoutTask(Entry entry, ScheduledFuture<?> timeoutTask) {
        if (waitingByGameId.get(entry.gameId) == entry) {
            entry.timeoutTask = timeoutTask;
        } else if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }

    synchronized Entry resolve(Long gameId, Long disconnectedUserId) {
        Entry entry = waitingByGameId.get(gameId);
        if (entry == null || !entry.disconnectedUserId.equals(disconnectedUserId)) {
            return null;
        }
        waitingByGameId.remove(gameId);
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel(false);
        }
        return entry;
    }

    synchronized boolean approve(Long gameId, Long userId) {
        Entry entry = waitingByGameId.get(gameId);
        return entry != null && entry.approvedUserIds.add(userId);
    }

    public synchronized Map<String, Object> snapshot(Long gameId) {
        return snapshot(gameId, Clock.systemUTC());
    }

    synchronized Map<String, Object> snapshot(Long gameId, Clock clock) {
        Entry entry = waitingByGameId.get(gameId);
        if (entry == null) {
            return Map.of("waiting", false);
        }
        return snapshot(entry, clock, entry.requiredUserIds);
    }

    synchronized Map<String, Object> snapshot(Entry entry, Clock clock, Set<Long> requiredUserIds) {
        Set<Long> requiredUserIdsCopy = new LinkedHashSet<>(requiredUserIds);
        entry.requiredUserIds.clear();
        entry.requiredUserIds.addAll(requiredUserIdsCopy);
        long remainingMillis = Math.max(0L, entry.deadline.toEpochMilli() - clock.millis());
        long remainingSeconds = (remainingMillis + 999L) / 1000L;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("waiting", true);
        state.put("gameId", entry.gameId);
        state.put("roomId", entry.roomId);
        state.put("disconnectedUserId", entry.disconnectedUserId);
        state.put("disconnectedUsername", entry.disconnectedUsername);
        state.put("deadlineEpochMs", entry.deadline.toEpochMilli());
        state.put("remainingSeconds", remainingSeconds);
        state.put("approvedUserIds", Set.copyOf(entry.approvedUserIds));
        state.put("requiredUserIds", Set.copyOf(entry.requiredUserIds));
        state.put("approvedCount", entry.approvedUserIds.stream().filter(entry.requiredUserIds::contains).count());
        state.put("requiredCount", entry.requiredUserIds.size());
        return state;
    }
}
