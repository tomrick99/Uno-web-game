package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.User;
import com.uno.entity.enums.GameStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class DisconnectGracePeriodService {

    private static final Logger log = LoggerFactory.getLogger(DisconnectGracePeriodService.class);

    private record GameSession(String username, Long gameId) {}

    private final DisconnectWaitingRegistry waitingRegistry;
    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final UserRepository userRepository;
    private final GameWebSocketService wsService;
    private final GameService gameService;
    private final TaskScheduler taskScheduler;
    private final Duration gracePeriod;
    private final Clock clock;
    private final Map<String, GameSession> gameSessions = new ConcurrentHashMap<>();

    @Autowired
    public DisconnectGracePeriodService(DisconnectWaitingRegistry waitingRegistry,
                                        GameRepository gameRepository,
                                        GamePlayerRepository gamePlayerRepository,
                                        UserRepository userRepository,
                                        GameWebSocketService wsService,
                                        GameService gameService,
                                        TaskScheduler taskScheduler,
                                        @Value("${uno.disconnect-grace-seconds:60}") long graceSeconds) {
        this(waitingRegistry, gameRepository, gamePlayerRepository, userRepository, wsService,
                gameService, taskScheduler, Duration.ofSeconds(graceSeconds), Clock.systemUTC());
    }

    DisconnectGracePeriodService(DisconnectWaitingRegistry waitingRegistry,
                                 GameRepository gameRepository,
                                 GamePlayerRepository gamePlayerRepository,
                                 UserRepository userRepository,
                                 GameWebSocketService wsService,
                                 GameService gameService,
                                 TaskScheduler taskScheduler,
                                 Duration gracePeriod,
                                 Clock clock) {
        this.waitingRegistry = waitingRegistry;
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.userRepository = userRepository;
        this.wsService = wsService;
        this.gameService = gameService;
        this.taskScheduler = taskScheduler;
        this.gracePeriod = gracePeriod;
        this.clock = clock;
    }

    @Transactional
    public void gameSessionSubscribed(String sessionId, String username, Long gameId) {
        if (sessionId == null || username == null || gameId == null) {
            return;
        }
        gameSessions.put(sessionId, new GameSession(username, gameId));

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return;
        }
        DisconnectWaitingRegistry.Entry resolved = waitingRegistry.resolve(gameId, user.getId());
        if (resolved != null) {
            log.info("[DISCONNECT] player reconnected gameId={} userId={}", gameId, user.getId());
            broadcast(resolved, "PLAYER_RECONNECTED", false,
                    username + " reconnected", currentOnlinePlayerIds(gameId, resolved.disconnectedUserId));
        }
    }

    @Transactional
    public void gameSessionDisconnected(String sessionId) {
        GameSession session = gameSessions.remove(sessionId);
        if (session == null || hasGameSession(session.username, session.gameId)) {
            return;
        }

        Game game = gameRepository.findById(session.gameId).orElse(null);
        User user = userRepository.findByUsername(session.username).orElse(null);
        if (game == null || user == null || game.getStatus() != GameStatus.PLAYING) {
            return;
        }
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user).orElse(null);
        if (player == null) {
            return;
        }
        if (waitingRegistry.get(game.getId()) != null) {
            return;
        }

        Instant deadline = clock.instant().plus(gracePeriod);
        DisconnectWaitingRegistry.Entry entry = waitingRegistry.begin(
                game.getId(), game.getRoom().getId(), user.getId(), user.getUsername(), deadline);
        if (!entry.disconnectedUserId.equals(user.getId())) {
            return;
        }

        ScheduledFuture<?> timeoutTask = taskScheduler.schedule(
                () -> resolveAndRemove(game.getId(), user.getId(), "DISCONNECT_TIMEOUT"), deadline);
        waitingRegistry.setTimeoutTask(entry, timeoutTask);
        log.info("[DISCONNECT] grace period started gameId={} userId={} deadline={}",
                game.getId(), user.getId(), deadline);
        broadcast(entry, "PLAYER_DISCONNECTED", true,
                user.getUsername() + " disconnected", currentOnlinePlayerIds(game.getId(), user.getId()));
    }

    @Transactional
    public Map<String, Object> voteToContinue(Long gameId, Long voterUserId) {
        DisconnectWaitingRegistry.Entry entry = waitingRegistry.get(gameId);
        if (entry == null) {
            throw new IllegalArgumentException("This game is not waiting for a disconnected player");
        }

        Set<Long> requiredUserIds = currentOnlinePlayerIds(gameId, entry.disconnectedUserId);
        if (!requiredUserIds.contains(voterUserId)) {
            throw new IllegalArgumentException("Only online players in this game can continue without the disconnected player");
        }
        waitingRegistry.approve(gameId, voterUserId);
        Map<String, Object> state = waitingRegistry.snapshot(entry, clock, requiredUserIds);
        broadcast(entry, "DISCONNECT_CONTINUE_VOTE", true, null, requiredUserIds);

        @SuppressWarnings("unchecked")
        Set<Long> approvedUserIds = state.get("approvedUserIds") instanceof Set<?> values
                ? (Set<Long>) values
                : Set.of();
        if (!requiredUserIds.isEmpty() && approvedUserIds.containsAll(requiredUserIds)) {
            resolveAndRemove(gameId, entry.disconnectedUserId, "UNANIMOUS_CONTINUE");
            return Map.of("waiting", false, "resolution", "UNANIMOUS_CONTINUE");
        }
        return state;
    }

    private void resolveAndRemove(Long gameId, Long disconnectedUserId, String reason) {
        DisconnectWaitingRegistry.Entry entry = waitingRegistry.resolve(gameId, disconnectedUserId);
        if (entry == null) {
            return;
        }
        log.info("[DISCONNECT] grace period resolved gameId={} userId={} reason={}",
                gameId, disconnectedUserId, reason);
        gameService.removeDisconnectedPlayer(gameId, disconnectedUserId, reason);
        broadcast(entry, reason, false, null, Set.of());
    }

    private boolean hasGameSession(String username, Long gameId) {
        return gameSessions.values().stream()
                .anyMatch(session -> session.gameId.equals(gameId) && session.username.equals(username));
    }

    private Set<Long> currentOnlinePlayerIds(Long gameId, Long disconnectedUserId) {
        Game game = gameRepository.findById(gameId).orElse(null);
        if (game == null) {
            return Set.of();
        }
        Set<String> onlineUsernames = new LinkedHashSet<>();
        gameSessions.values().stream()
                .filter(session -> session.gameId.equals(gameId))
                .map(GameSession::username)
                .forEach(onlineUsernames::add);

        Set<Long> onlinePlayerIds = new LinkedHashSet<>();
        for (GamePlayer player : gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)) {
            if (!player.getUser().getId().equals(disconnectedUserId)
                    && onlineUsernames.contains(player.getUser().getUsername())) {
                onlinePlayerIds.add(player.getUser().getId());
            }
        }
        return onlinePlayerIds;
    }

    private void broadcast(DisconnectWaitingRegistry.Entry entry,
                           String event,
                           boolean waiting,
                           String message,
                           Set<Long> requiredUserIds) {
        Map<String, Object> state = waiting
                ? waitingRegistry.snapshot(entry, clock, requiredUserIds)
                : Map.of(
                        "waiting", false,
                        "gameId", entry.gameId,
                        "roomId", entry.roomId,
                        "disconnectedUserId", entry.disconnectedUserId,
                        "disconnectedUsername", entry.disconnectedUsername);
        wsService.broadcastDisconnectWaitingState(entry.gameId, entry.roomId, event, state, message);
    }
}
