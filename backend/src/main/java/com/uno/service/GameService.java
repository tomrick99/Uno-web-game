package com.uno.service;

import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.dto.realtime.PublicPlayerInfo;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.PendingDrawType;
import com.uno.entity.enums.RoomStatus;
import com.uno.model.Card;
import com.uno.model.Deck;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
@Transactional
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private record PlayValidation(boolean playable, String reason) {}

    private record PrivateHandPatchDelivery(String username,
                                            Long roomId,
                                            Long gameId,
                                            Long userId,
                                            PrivateHandPatch patch) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final GameWebSocketService wsService;
    private final RoomService roomService;
    private final ConcurrentHashMap<Long, ReentrantLock> roomLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> roomStateVersions = new ConcurrentHashMap<>();

    public GameService(GameRepository gameRepository,
                       GamePlayerRepository gamePlayerRepository,
                       RoomRepository roomRepository,
                       UserRepository userRepository,
                       GameWebSocketService wsService,
                       RoomService roomService) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.wsService = wsService;
        this.roomService = roomService;
    }

    public Map<String, Object> joinGame(Long roomId, Long userId) {
        return withRoomLock(roomId, () -> doJoinGame(roomId, userId));
    }

    public Map<String, Object> playCard(Long gameId, Long userId, int cardIndex, CardColor chosenColor) {
        Long roomId = getRoomIdByGameId(gameId);
        long startedAt = System.nanoTime();
        try {
            return withRoomLock(roomId, () -> doPlayCard(gameId, userId, cardIndex, chosenColor));
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=playCard roomId={} userId={} costMs={}", roomId, userId, costMs);
        }
    }

    public Map<String, Object> drawCard(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        long startedAt = System.nanoTime();
        try {
            return withRoomLock(roomId, () -> doDrawCard(gameId, userId));
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=drawCard roomId={} userId={} costMs={}", roomId, userId, costMs);
        }
    }

    public Map<String, Object> drawPenalty(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        long startedAt = System.nanoTime();
        try {
            return withRoomLock(roomId, () -> doDrawPenalty(gameId, userId));
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=drawPenalty roomId={} userId={} costMs={}", roomId, userId, costMs);
        }
    }

    public Map<String, Object> readyForRematch(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        return withRoomLock(roomId, () -> doReadyForRematch(gameId, userId));
    }

    public Map<String, Object> leaveRoom(Long roomId, Long userId) {
        return withRoomLock(roomId, () -> doLeaveRoom(roomId, userId));
    }

    public Map<String, Object> restartGame(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        return withRoomLock(roomId, () -> doRestartGame(gameId, userId));
    }

    public void deleteRoomByAdmin(Long roomId, String operatorName) {
        withRoomLock(roomId, () -> {
            Room room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room not found"));

            Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
            Long gameId = gameOpt.map(Game::getId).orElse(null);
            String message = "Room deleted by admin";
            if (operatorName != null && !operatorName.isBlank()) {
                message = message + " (" + operatorName + ")";
            }

            wsService.broadcastRoomDeleted(roomId, gameId, message);

            if (gameOpt.isPresent()) {
                Game game = gameOpt.get();
                gamePlayerRepository.deleteAllByGame(game);
                gameRepository.delete(game);
            }
            roomRepository.delete(room);
            return null;
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGameStateByRoomId(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        return getGameState(game.getId());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerHandByRoomId(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        return getPlayerHand(game.getId(), userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRealtimeSnapshotByRoomId(Long roomId, Long userId) {
        return withRoomLock(roomId, () -> {
            Room room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
            Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
            Long gameId = gameOpt.map(Game::getId).orElse(null);
            Map<String, Object> snapshot = buildRealtimeSnapshot(roomId, gameId, userId);
            @SuppressWarnings("unchecked")
            Map<String, Object> gameState = snapshot.get("gameState") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : null;
            log.info("[SYNC] action=snapshot roomId={} userId={} currentTurn={} version={}",
                    roomId,
                    userId,
                    gameState != null ? gameState.get("currentTurn") : null,
                    snapshot.get("version"));
            return snapshot;
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGameState(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        Map<String, Object> state = new LinkedHashMap<>();
        Long roomId = game.getRoom().getId();
        state.put("gameId", game.getId());
        state.put("roomId", roomId);
        state.put("roomCode", game.getRoom().getRoomCode());
        state.put("roomStatus", game.getRoom().getStatus().name());
        state.put("maxPlayers", game.getRoom().getMaxPlayers());
        state.put("totalRounds", game.getRoom().getTotalRounds());
        state.put("roundTimeLimitMinutes", game.getRoom().getRoundTimeLimitMinutes());
        state.put("gameMode", resolveGameMode(game).name());
        state.put("status", game.getStatus().name());
        Card topCard = getTopDiscard(game);
        CardColor effectiveColor = resolveCurrentColor(game.getCurrentColor(), topCard);
        state.put("currentTurn", game.getCurrentTurn());
        state.put("currentColor", effectiveColor.name());
        state.put("pendingDrawCount", game.getPendingDrawCount());
        state.put("pendingDrawType", game.getPendingDrawType() != null ? game.getPendingDrawType().name() : PendingDrawType.NONE.name());
        state.put("lastPenaltyPlayerId", game.getLastPenaltyPlayerId());
        state.put("clockwise", game.isClockwise());
        state.put("direction", game.isClockwise() ? 1 : -1);
        state.put("canRestart", game.getStatus() == GameStatus.FINISHED);

        state.put("topCard", topCard != null ? cardToMap(topCard) : null);
        state.put("drawPileSize", fromJson(game.getDrawPileJson()).size());

        List<Map<String, Object>> players = new ArrayList<>();
        List<Long> rematchReadyPlayerIds = new ArrayList<>();
        Long winnerId = null;
        for (GamePlayer gp : gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)) {
            List<Card> hand = sortHandCards(gp.getHandCards());
            if (game.getStatus() == GameStatus.FINISHED && hand.isEmpty()) {
                winnerId = gp.getUser().getId();
            }
            if (gp.isRematchReady()) {
                rematchReadyPlayerIds.add(gp.getUser().getId());
            }

            Map<String, Object> player = new LinkedHashMap<>();
            player.put("userId", gp.getUser().getId());
            player.put("username", gp.getUser().getUsername());
            player.put("handCount", hand.size());
            player.put("seatIndex", gp.getSeatIndex());
            player.put("saidUno", gp.isSaidUno());
            player.put("rematchReady", gp.isRematchReady());
            players.add(player);
        }

        state.put("players", players);
        state.put("winnerId", winnerId);
        state.put("rematchReadyPlayerIds", rematchReadyPlayerIds);
        state.put("rematchReadyCount", rematchReadyPlayerIds.size());
        state.put("allPlayersReadyForRematch",
                game.getStatus() == GameStatus.FINISHED
                        && !players.isEmpty()
                        && rematchReadyPlayerIds.size() == players.size());
        state.put("version", getCurrentStateVersion(roomId, game));
        return state;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerHand(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Player is not in this game"));

        List<Card> hand = sortHandCards(player.getHandCards());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card card : hand) {
            result.add(cardToMap(card));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildRealtimeSnapshot(Long roomId, Long gameId, Long userId) {
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("type", "FULL_SNAPSHOT");
            Map<String, Object> roomState = roomService.getRoomState(roomId);
            Map<String, Object> gameState = gameId != null ? getGameState(gameId) : null;
            Long version = gameState != null
                    ? asLong(gameState.get("version"))
                    : getCurrentStateVersion(roomId, null);
            roomState.put("version", version);
            snapshot.put("roomState", roomState);
            snapshot.put("gameState", gameState);
            snapshot.put("handCards", gameId != null ? getPlayerHand(gameId, userId) : new ArrayList<>());
            snapshot.put("roomVersion", version);
            snapshot.put("gameVersion", version);
            snapshot.put("handVersion", version);
            snapshot.put("version", version);
            return snapshot;
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=buildRealtimeSnapshot roomId={} gameId={} userId={} costMs={}",
                    roomId, gameId, userId, costMs);
        }
    }

    private Map<String, Object> doJoinGame(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseGet(() -> createWaitingGame(room));

        Optional<GamePlayer> existingPlayer = gamePlayerRepository.findByGameAndUser(game, user);
        if (existingPlayer.isEmpty()) {
            if (room.getStatus() != RoomStatus.WAITING || game.getStatus() == GameStatus.FINISHED) {
                throw new IllegalArgumentException("房间已开始或已结束，无法加入");
            }

            List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
            int playerCount = players.size();
            if (playerCount >= room.getMaxPlayers()) {
                throw new IllegalArgumentException("房间已满");
            }

            GamePlayer gp = new GamePlayer();
            gp.setGame(game);
            gp.setUser(user);
            gp.setSeatIndex(playerCount);
            gp.setSaidUno(false);
            gp.setRematchReady(false);
            gp.setHandCards(new ArrayList<>());
            gamePlayerRepository.save(gp);
            bumpStateVersion(roomId);
            log.info("[UNO] room joined roomId={} players={}", roomId, playerCount + 1);

            Map<String, Object> roomState = getRoomStateWithVersion(room, game);
            wsService.broadcastRoomState(roomState, "PLAYER_JOINED", user.getUsername() + " joined the room");
            wsService.broadcastLobbyRoomState(roomState, "PLAYER_JOINED", user.getUsername() + " joined the room");
        }

        int currentPlayerCount = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game).size();
        if (shouldStartGame(room, currentPlayerCount) && game.getStatus() == GameStatus.WAITING) {
            startGame(game);
            room.setStatus(RoomStatus.PLAYING);
            roomRepository.save(room);
            long version = bumpStateVersion(room.getId());
            log.info("[SYNC] action=gameStarted roomId={} gameId={} currentTurn={} version={}",
                    room.getId(), game.getId(), game.getCurrentTurn(), version);

            Map<String, Object> roomState = getRoomStateWithVersion(room, game);
            wsService.broadcastRoomState(roomState, "GAME_STARTED", "Game started");
            wsService.broadcastLobbyRoomState(roomState, "ROOM_UPDATED", "Game started");
            List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
            wsService.broadcastPublicGamePatch(buildPublicGamePatch(game, players, "GAME_STARTED", userId, user.getUsername(), "Game started"));
            sendPrivateHandPatches(game, players, "HAND_UPDATED");
        } else if (game.getStatus() == GameStatus.PLAYING || game.getStatus() == GameStatus.FINISHED) {
            Map<String, Object> roomState = getRoomStateWithVersion(room, game);
            wsService.broadcastRoomState(roomState, "PLAYER_SYNC", null);
            wsService.broadcastLobbyRoomState(roomState, "ROOM_UPDATED", null);
        }

        return buildRealtimeSnapshot(room.getId(), game.getId(), userId);
    }

    private Map<String, Object> doPlayCard(Long gameId, Long userId, int cardIndex, CardColor chosenColor) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));

        if (game.getStatus() != GameStatus.PLAYING) {
            throw new IllegalArgumentException("Game is not in playing state");
        }
        if (!userId.equals(game.getCurrentTurn())) {
            logUnoPlay(userId, null, getTopDiscard(game), game.getCurrentColor(), game.getPendingDrawCount(), false, false,
                    "not current player", game.getCurrentTurn());
            throw new IllegalArgumentException("It is not your turn to play");
        }

        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Player is not in this game"));

        List<Card> hand = sortHandCards(player.getHandCards());
        if (cardIndex < 0 || cardIndex >= hand.size()) {
            throw new IllegalArgumentException("Invalid card index");
        }

        Card card = hand.get(cardIndex);
        Card topCard = getTopDiscard(game);
        CardColor effectiveColor = resolveCurrentColor(game.getCurrentColor(), topCard);
        log.info("[UNO-BE] try play player={} card={} topCard={} currentColor={} pendingDrawCount={} chosenColor={}",
                userId,
                formatCard(card),
                formatCard(topCard),
                effectiveColor,
                game.getPendingDrawCount(),
                chosenColor != null ? chosenColor.name() : "none");
        PlayValidation validation = validatePlay(game, card, topCard, effectiveColor);
        if (!validation.playable()) {
            logUnoPlay(userId, card, topCard, effectiveColor, game.getPendingDrawCount(), true, false, validation.reason(), game.getCurrentTurn());
            throw new IllegalArgumentException("这张牌当前不能出");
        }
        if (requiresColorSelection(card)
                && (chosenColor == null || chosenColor == CardColor.WILD)) {
            throw new IllegalArgumentException("请选择颜色");
        }
        validateChosenColor(card, chosenColor);

        hand.remove(cardIndex);
        setSortedHand(player, hand);
        player.setSaidUno(hand.size() == 1);
        gamePlayerRepository.save(player);

        List<Card> discardPile = fromJson(game.getDiscardPileJson());
        discardPile.add(card);
        game.setDiscardPileJson(toJson(discardPile));

        Long oldCurrentPlayerId = game.getCurrentTurn();
        boolean handledTurnAdvance = processCardEffect(game, player, card, chosenColor, userId);
        if (!handledTurnAdvance) {
            moveToNextPlayer(game);
        }
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        List<Card> remainingHand = player.getHandCards();
        boolean gameFinished = remainingHand.isEmpty();
        GamePlayer autoPenaltyPlayer = null;
        if (gameFinished) {
            game.setStatus(GameStatus.FINISHED);
            game.setCurrentTurn(null);
            clearPendingDraw(game);
        } else {
            autoPenaltyPlayer = resolveUnstackablePenalty(game, players);
        }
        gameRepository.save(game);
        long version = bumpStateVersion(game.getRoom().getId());
        log.info("[SYNC] action=playCardApplied roomId={} gameId={} userId={} playedCard={} oldTurn={} newTurn={} currentPlayerIndex={} direction={} pendingPenalty={} gameStatus={} version={}",
                game.getRoom().getId(),
                game.getId(),
                userId,
                formatCard(card),
                oldCurrentPlayerId,
                game.getCurrentTurn(),
                resolveCurrentPlayerIndex(game),
                game.isClockwise() ? 1 : -1,
                game.getPendingDrawCount(),
                game.getStatus(),
                version);
        logUnoPlay(userId, card, topCard, effectiveColor, game.getPendingDrawCount(), true, true, "accepted", game.getCurrentTurn());

        Map<String, Object> finishedRoomState = null;
        PublicGamePatch publicPatch;
        List<PrivateHandPatchDelivery> privateHandPatches = new ArrayList<>();
        if (gameFinished) {
            roomService.closeRoom(game.getRoom());
            int playerCount = players.size();
            log.info("[UNO] game finished gameId={} roomId={} winner={}", game.getId(), game.getRoom().getId(), userId);
            log.info("[UNO] broadcasting FINISHED to /topic/games/{}", game.getId());
            log.info("[UNO] broadcasting room update to /topic/rooms/{}", game.getRoom().getId());
            log.info("[UNO] broadcast game finished gameId={} roomId={} players={}", game.getId(), game.getRoom().getId(), playerCount);

            finishedRoomState = getRoomStateWithVersion(game.getRoom(), game);
            publicPatch = buildPublicGamePatch(game, players, "GAME_FINISHED", userId, user.getUsername(), user.getUsername() + " wins!");
        } else {
            String display = card.type() == CardType.NUMBER ? String.valueOf(card.value()) : card.type().name();
            publicPatch = buildPublicGamePatch(game, players, "CARD_PLAYED", userId, user.getUsername(), user.getUsername() + " played " + display);
        }
        privateHandPatches.add(buildPrivateHandPatchDelivery(game, player, "HAND_UPDATED"));
        if (autoPenaltyPlayer != null && autoPenaltyPlayer != player) {
            privateHandPatches.add(buildPrivateHandPatchDelivery(game, autoPenaltyPlayer, "HAND_UPDATED"));
        }

        Map<String, Object> roomState = finishedRoomState;
        List<PrivateHandPatchDelivery> deliveries = List.copyOf(privateHandPatches);
        publishAfterCommit("playCard", () -> {
            if (roomState != null) {
                wsService.broadcastRoomState(roomState, "GAME_FINISHED", user.getUsername() + " 赢得了本局");
                wsService.broadcastLobbyRoomState(roomState, "ROOM_UPDATED", "Game finished");
            }
            wsService.broadcastPublicGamePatch(publicPatch);
            deliveries.forEach(this::sendPrivateHandPatch);
        });

        return operationAck(game.getRoom().getId(), game.getId(), getCurrentStateVersion(game.getRoom().getId(), game), "CARD_PLAYED");
    }

    private Map<String, Object> doDrawCard(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));

        if (game.getStatus() != GameStatus.PLAYING) {
            throw new IllegalArgumentException("Game is not in playing state");
        }
        if (!userId.equals(game.getCurrentTurn())) {
            throw new IllegalArgumentException("It is not your turn to draw");
        }

        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Player is not in this game"));

        if (hasPendingDraw(game)) {
            throw new IllegalArgumentException("Must resolve the pending draw stack by playing +2/+4 or drawing the penalty cards");
        }

        Deck deck = getDeck(game);
        Card drawn = deck.drawCard();
        if (drawn == null) {
            throw new RuntimeException("牌堆已空");
        }

        List<Card> hand = player.getHandCards();
        hand.add(drawn);
        setSortedHand(player, hand);
        player.setSaidUno(false);
        gamePlayerRepository.save(player);

        saveDeckState(game, deck);
        moveToNextPlayer(game);
        gameRepository.save(game);
        long version = bumpStateVersion(game.getRoom().getId());
        log.info("[SYNC] action=drawCardApplied roomId={} gameId={} userId={} drawnCard={} newTurn={} currentPlayerIndex={} direction={} pendingPenalty={} gameStatus={} version={}",
                game.getRoom().getId(),
                game.getId(),
                userId,
                formatCard(drawn),
                game.getCurrentTurn(),
                resolveCurrentPlayerIndex(game),
                game.isClockwise() ? 1 : -1,
                game.getPendingDrawCount(),
                game.getStatus(),
                version);

        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        PublicGamePatch publicPatch = buildPublicGamePatch(game, players, "CARD_DRAWN_PUBLIC", userId, user.getUsername(), user.getUsername() + " drew a card");
        PrivateHandPatchDelivery privateHandPatch = buildPrivateHandPatchDelivery(game, player, "HAND_UPDATED");
        publishAfterCommit("drawCard", () -> {
            wsService.broadcastPublicGamePatch(publicPatch);
            sendPrivateHandPatch(privateHandPatch);
        });
        return operationAck(game.getRoom().getId(), game.getId(), getCurrentStateVersion(game.getRoom().getId(), game), "CARD_DRAWN_PUBLIC");
    }

    private Map<String, Object> doDrawPenalty(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));

        if (game.getStatus() != GameStatus.PLAYING) {
            throw new IllegalArgumentException("Game is not in playing state");
        }
        if (!userId.equals(game.getCurrentTurn())) {
            throw new IllegalArgumentException("It is not your turn");
        }
        if (!hasPendingDraw(game)) {
            throw new IllegalArgumentException("There is no pending draw penalty to draw");
        }

        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Player is not in this game"));

        int drawCount = game.getPendingDrawCount();
        drawCardsToPlayer(game, player, drawCount);

        clearPendingDraw(game);
        moveToNextPlayer(game);
        gameRepository.save(game);
        long version = bumpStateVersion(game.getRoom().getId());

        log.info("[SYNC] action=penaltyApplied roomId={} gameId={} userId={} drawCount={} newTurn={} currentPlayerIndex={} direction={} pendingPenalty={} gameStatus={} version={}",
                game.getRoom().getId(),
                game.getId(),
                userId,
                drawCount,
                game.getCurrentTurn(),
                resolveCurrentPlayerIndex(game),
                game.isClockwise() ? 1 : -1,
                game.getPendingDrawCount(),
                game.getStatus(),
                version);
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        PublicGamePatch publicPatch = buildPublicGamePatch(game, players, "PENALTY_UPDATED", userId, user.getUsername(), user.getUsername() + " drew " + drawCount + " penalty cards");
        PrivateHandPatchDelivery privateHandPatch = buildPrivateHandPatchDelivery(game, player, "HAND_UPDATED");
        publishAfterCommit("drawPenalty", () -> {
            wsService.broadcastPublicGamePatch(publicPatch);
            sendPrivateHandPatch(privateHandPatch);
        });
        return operationAck(game.getRoom().getId(), game.getId(), getCurrentStateVersion(game.getRoom().getId(), game), "PENALTY_UPDATED");
    }

    private Map<String, Object> doReadyForRematch(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Only players in this game can request a rematch"));

        if (game.getStatus() != GameStatus.FINISHED) {
            throw new IllegalArgumentException("Rematch is only available after the game finishes");
        }

        if (!player.isRematchReady()) {
            player.setRematchReady(true);
            gamePlayerRepository.save(player);
            long version = bumpStateVersion(game.getRoom().getId());
            log.info("[SYNC] action=rematchReady roomId={} gameId={} userId={} version={}",
                    game.getRoom().getId(), game.getId(), userId, version);
        }

        if (allPlayersReadyForRematch(game)) {
            return doRestartGame(gameId, userId, true);
        }

        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        wsService.broadcastPublicGamePatch(buildPublicGamePatch(game, players, "REMATCH_READY", userId, user.getUsername(),
                user.getUsername() + " is ready for a rematch. Waiting for the other player."));
        return operationAck(game.getRoom().getId(), game.getId(), getCurrentStateVersion(game.getRoom().getId(), game), "REMATCH_READY");
    }

    private Map<String, Object> doRestartGame(Long gameId, Long userId) {
        return doRestartGame(gameId, userId, false);
    }

    private Map<String, Object> doRestartGame(Long gameId, Long userId, boolean rematchApproved) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        User user = userRepository.getReferenceById(userId);
        gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("Only current players can restart the game"));

        if (game.getStatus() != GameStatus.FINISHED) {
            throw new IllegalArgumentException("当前游戏还未结束");
        }

        if (!rematchApproved && !allPlayersReadyForRematch(game)) {
            throw new IllegalArgumentException("Both players must agree before restarting");
        }

        Room room = game.getRoom();
        room.setStatus(RoomStatus.PLAYING);
        roomRepository.save(room);

        startGame(game);
        roomService.reopenRoom(room);
        long version = bumpStateVersion(room.getId());
        log.info("[SYNC] action=gameRestarted roomId={} gameId={} userId={} currentTurn={} version={}",
                room.getId(), game.getId(), userId, game.getCurrentTurn(), version);

        Map<String, Object> roomState = getRoomStateWithVersion(room, game);
        wsService.broadcastRoomState(roomState, "GAME_RESTARTED", "Rematch started");
        wsService.broadcastLobbyRoomState(roomState, "ROOM_UPDATED", "Rematch started");
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        wsService.broadcastPublicGamePatch(buildPublicGamePatch(game, players, "GAME_RESTARTED", userId, user.getUsername(), "Game restarted"));
        sendPrivateHandPatches(game, players, "HAND_UPDATED");

        return operationAck(room.getId(), game.getId(), getCurrentStateVersion(room.getId(), game), "GAME_RESTARTED");
    }

    private Map<String, Object> doLeaveRoom(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
        if (gameOpt.isEmpty()) {
            if (room.getHost().getId().equals(userId)) {
                bumpStateVersion(roomId);
                wsService.broadcastLobbyRoomRemoved(roomId, "ROOM_REMOVED", user.getUsername() + " left the room");
                roomRepository.delete(room);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roomClosed", true);
            result.put("message", "Left room");
            return result;
        }

        Game game = gameOpt.get();
        Optional<GamePlayer> playerOpt = gamePlayerRepository.findByGameAndUser(game, user);
        if (playerOpt.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roomClosed", room.getStatus() != RoomStatus.WAITING);
            result.put("message", "Already left room");
            return result;
        }

        log.info("[UNO] player left roomId={} playerId={}", roomId, userId);
        gamePlayerRepository.delete(playerOpt.get());
        List<GamePlayer> remainingPlayers = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);

        if (remainingPlayers.isEmpty()) {
            long version = bumpStateVersion(roomId);
            log.info("[SYNC] action=lastPlayerLeft roomId={} gameId={} userId={} version={}",
                    roomId, game.getId(), userId, version);
            log.info("[UNO] room closed or updated roomId={} status={}", roomId, RoomStatus.CLOSED);
            wsService.broadcastRoomDeleted(roomId, game.getId(), user.getUsername() + " left the room");
            gameRepository.delete(game);
            roomRepository.delete(room);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roomClosed", true);
            result.put("message", "Left room");
            return result;
        }

        if (room.getStatus() != RoomStatus.WAITING || game.getStatus() != GameStatus.WAITING) {
            room.setStatus(RoomStatus.CLOSED);
            roomRepository.save(room);
            long version = bumpStateVersion(roomId);
            log.info("[SYNC] action=playerLeftClosedRoom roomId={} gameId={} userId={} version={}",
                    roomId, game.getId(), userId, version);
            log.info("[UNO] room closed or updated roomId={} status={}", roomId, room.getStatus());
            log.info("[UNO] broadcasting PLAYER_LEFT roomId={}", roomId);
            wsService.broadcastRoomState(getRoomStateWithVersion(room, game), "PLAYER_LEFT", "Opponent left the room");
            wsService.broadcastRoomDeleted(roomId, game.getId(), user.getUsername() + " left the room");
            gamePlayerRepository.deleteAllByGame(game);
            gameRepository.delete(game);
            roomRepository.delete(room);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roomClosed", true);
            result.put("message", "Left room");
            return result;
        }

        reseatPlayers(remainingPlayers);
        if (room.getHost().getId().equals(userId)) {
            room.setHost(remainingPlayers.get(0).getUser());
        }
        room.setStatus(RoomStatus.WAITING);
        roomRepository.save(room);
        long version = bumpStateVersion(roomId);
        log.info("[SYNC] action=playerLeftWaitingRoom roomId={} gameId={} userId={} version={}",
                roomId, game.getId(), userId, version);

        log.info("[UNO] room closed or updated roomId={} status={}", roomId, room.getStatus());
        log.info("[UNO] broadcasting PLAYER_LEFT roomId={}", roomId);
        Map<String, Object> roomState = getRoomStateWithVersion(room, game);
        wsService.broadcastRoomState(roomState, "PLAYER_LEFT", user.getUsername() + " left the room");
        wsService.broadcastLobbyRoomState(roomState, "PLAYER_LEFT", user.getUsername() + " left the room");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomClosed", false);
        result.put("message", "Left room");
        result.put("roomState", roomState);
        return result;
    }

    private Game createWaitingGame(Room room) {
        Game game = new Game();
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        game.setClockwise(true);
        Game savedGame = gameRepository.save(game);
        bumpStateVersion(room.getId());
        return savedGame;
    }

    private void startGame(Game game) {
        startGame(game, new Deck(resolveGameMode(game)));
    }

    void startGame(Game game, Deck deck) {
        game.setStatus(GameStatus.PLAYING);
        game.setClockwise(true);
        clearPendingDraw(game);
        clearRematchReady(game);
        dealCards(game, deck);
        gameRepository.save(game);
    }

    private void dealCards(Game game, Deck deck) {
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        if (players.size() < 2) {
            throw new IllegalStateException("At least two players are required to start the game");
        }

        for (GamePlayer player : players) {
            List<Card> hand = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Card card = deck.drawCard();
                if (card == null) {
                    throw new RuntimeException("Deck is empty and cards cannot be dealt");
                }
                hand.add(card);
            }
            setSortedHand(player, hand);
            player.setSaidUno(false);
            player.setRematchReady(false);
            gamePlayerRepository.save(player);
        }

        Card firstDiscard = drawInitialNumberCard(deck);
        deck.discard(firstDiscard);

        saveDeckState(game, deck);
        game.setCurrentColor(firstDiscard.color());
        game.setCurrentTurn(players.get(0).getUser().getId());
    }

    Card drawInitialNumberCard(Deck deck) {
        List<Card> rejectedCards = new ArrayList<>();
        Card candidate;
        while ((candidate = deck.drawCard()) != null) {
            if (candidate.color() != null
                    && candidate.color() != CardColor.WILD
                    && candidate.type() == CardType.NUMBER) {
                deck.getDrawPile().addAll(rejectedCards);
                deck.shuffle();
                return candidate;
            }
            rejectedCards.add(candidate);
        }

        deck.getDrawPile().addAll(rejectedCards);
        deck.shuffle();
        throw new IllegalStateException("Deck does not contain a colored number card for the initial discard");
    }

    private PlayValidation validatePlay(Game game, Card card, Card topCard, CardColor currentColor) {
        if (card == null || card.color() == null || card.type() == null) {
            return new PlayValidation(false, "invalid card");
        }
        if (!isNoMercy(game) && isNoMercyOnlyCard(card)) {
            logCanPlay(card, topCard, currentColor, game, false, "No Mercy card is not allowed in Classic");
            return new PlayValidation(false, "No Mercy card is not allowed in Classic");
        }
        if (hasPendingDraw(game)) {
            if (isNoMercy(game)) {
                if (canStackNoMercyPenalty(card, topCard)) {
                    logCanPlay(card, topCard, currentColor, game, true, "pending draw allows equal or higher draw penalty card");
                    return new PlayValidation(true, "pending draw allows equal or higher draw penalty card");
                }
                logCanPlay(card, topCard, currentColor, game, false, "pending draw only allows equal or higher draw penalty cards");
                return new PlayValidation(false, "pending draw only allows equal or higher draw penalty cards");
            }

            if (canStackClassicPenalty(card, topCard)) {
                logCanPlay(card, topCard, currentColor, game, true, "pending draw allows equal or higher draw penalty card");
                return new PlayValidation(true, "pending draw allows equal or higher draw penalty card");
            }
            logCanPlay(card, topCard, currentColor, game, false, "pending draw only allows equal or higher draw penalty cards");
            return new PlayValidation(false, "pending draw only allows equal or higher draw penalty cards");
        }

        if (isWildCard(card)) {
            logCanPlay(card, topCard, currentColor, game, true, "wild card");
            return new PlayValidation(true, "wild card");
        }
        if (card.color() == currentColor) {
            logCanPlay(card, topCard, currentColor, game, true, "color match");
            return new PlayValidation(true, "color match");
        }
        if (topCard != null
                && card.type() == CardType.NUMBER
                && topCard.type() == CardType.NUMBER
                && card.value() == topCard.value()) {
            logCanPlay(card, topCard, currentColor, game, true, "number match");
            return new PlayValidation(true, "number match");
        }
        if (topCard != null
                && card.type() != CardType.NUMBER
                && topCard.type() != CardType.NUMBER
                && card.type() == topCard.type()) {
            logCanPlay(card, topCard, currentColor, game, true, "type match");
            return new PlayValidation(true, "type match");
        }

        logCanPlay(card, topCard, currentColor, game, false, "color/number/type mismatch");
        return new PlayValidation(false, "color/number/type mismatch");
    }

    private boolean processCardEffect(Game game, GamePlayer player, Card card, CardColor chosenColor, Long playerId) {
        if (card.type() == null) {
            return false;
        }

        boolean handledTurnAdvance = false;
        switch (card.type()) {
            case SKIP:
                moveToNextPlayer(game);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case REVERSE:
                game.setClockwise(!game.isClockwise());
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case DRAW_TWO:
                addPendingDraw(game, playerId, card, chosenColor);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case DRAW_FOUR:
                addPendingDraw(game, playerId, card, chosenColor);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case DISCARD_ALL_COLOR:
                discardAllMatchingColor(game, player, card.color());
                break;
            case SKIP_ALL:
                game.setCurrentTurn(playerId);
                handledTurnAdvance = true;
                break;
            case WILD_DRAW_FOUR:
                if (chosenColor == null) {
                    throw new IllegalArgumentException("万能 +4 必须选择颜色");
                }
                game.setCurrentColor(chosenColor);
                addPendingDraw(game, playerId, card, chosenColor);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case WILD_DRAW_SIX:
            case WILD_DRAW_TEN:
                if (chosenColor == null) {
                    throw new IllegalArgumentException("Wild draw card requires a color");
                }
                game.setCurrentColor(chosenColor);
                addPendingDraw(game, playerId, card, chosenColor);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case WILD_REVERSE_DRAW_FOUR:
                if (chosenColor == null) {
                    throw new IllegalArgumentException("Wild reverse draw four requires a color");
                }
                game.setCurrentColor(chosenColor);
                game.setClockwise(!game.isClockwise());
                addPendingDraw(game, playerId, card, chosenColor);
                moveToNextPlayer(game);
                handledTurnAdvance = true;
                break;
            case WILD:
                if (chosenColor == null) {
                    throw new IllegalArgumentException("万能牌必须选择颜色");
                }
                game.setCurrentColor(chosenColor);
                break;
            default:
                break;
        }

        if (card.color() != null && card.color() != CardColor.WILD) {
            game.setCurrentColor(card.color());
        }
        return handledTurnAdvance;
    }

    private void validateChosenColor(Card card, CardColor chosenColor) {
        if (card == null || card.type() == null) {
            throw new IllegalArgumentException("Invalid card");
        }
        if (!requiresColorSelection(card)) {
            return;
        }
        if (chosenColor == null || chosenColor == CardColor.WILD) {
            throw new IllegalArgumentException("Choose a color: RED / YELLOW / GREEN / BLUE");
        }
    }

    private CardColor resolveCurrentColor(CardColor currentColor, Card topCard) {
        if (currentColor != null && currentColor != CardColor.WILD) {
            return currentColor;
        }
        if (topCard != null && topCard.color() != null && topCard.color() != CardColor.WILD) {
            return topCard.color();
        }
        return CardColor.RED;
    }

    private boolean hasPendingDraw(Game game) {
        return game != null
                && game.getPendingDrawCount() > 0
                && game.getPendingDrawType() != null
                && game.getPendingDrawType() != PendingDrawType.NONE;
    }

    private void clearPendingDraw(Game game) {
        if (game == null) {
            return;
        }
        game.setPendingDrawCount(0);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setLastPenaltyPlayerId(null);
    }

    GamePlayer resolveUnstackablePenalty(Game game, List<GamePlayer> players) {
        if (!hasPendingDraw(game) || game.getCurrentTurn() == null) {
            return null;
        }
        GamePlayer target = players.stream()
                .filter(player -> game.getCurrentTurn().equals(player.getUser().getId()))
                .findFirst()
                .orElse(null);
        if (target == null || hasStackablePenaltyResponse(game, target)) {
            return null;
        }

        int drawCount = game.getPendingDrawCount();
        drawCardsToPlayer(game, target, drawCount);
        clearPendingDraw(game);
        moveToNextPlayer(game);
        log.info("[UNO] auto penalty player={} drawCount={} nextPlayer={}",
                target.getUser().getId(), drawCount, game.getCurrentTurn());
        return target;
    }

    boolean hasStackablePenaltyResponse(Game game, GamePlayer player) {
        Card topCard = getTopDiscard(game);
        return player != null && player.getHandCards() != null && player.getHandCards().stream().anyMatch(card ->
                isNoMercy(game) ? canStackNoMercyPenalty(card, topCard) : canStackClassicPenalty(card, topCard));
    }

    private void addPendingDraw(Game game, Long playerId, Card card, CardColor chosenColor) {
        if (card == null || card.type() == null) {
            return;
        }

        int amount = getDrawPenaltyAmount(card);
        if (amount <= 0) {
            return;
        }

        if (isNoMercy(game)) {
            game.setPendingDrawType(PendingDrawType.DRAW_STACK);
        } else if (card.type() == CardType.DRAW_TWO) {
            game.setPendingDrawType(PendingDrawType.DRAW_TWO_CHAIN);
        } else if (card.type() == CardType.WILD_DRAW_FOUR) {
            game.setPendingDrawType(PendingDrawType.WILD_DRAW_FOUR_CHAIN);
        }

        game.setPendingDrawCount(game.getPendingDrawCount() + amount);
        if (isWildCard(card)) {
            if (chosenColor != null && chosenColor != CardColor.WILD) {
                game.setCurrentColor(chosenColor);
            }
        }

        game.setLastPenaltyPlayerId(playerId);
        logPenaltyStack(card, playerId, game);
    }

    private boolean isPenaltyStackCard(Card card) {
        return isDrawPenaltyCard(card);
    }

    private int penaltyValue(Card card) {
        return getDrawPenaltyAmount(card);
    }

    boolean canStackNoMercyPenalty(Card card, Card topCard) {
        int candidate = penaltyValue(card);
        if (candidate <= 0) {
            return false;
        }
        int required = penaltyValue(topCard);
        return candidate >= required;
    }

    boolean canStackClassicPenalty(Card card, Card topCard) {
        return card != null
                && (card.type() == CardType.DRAW_TWO || card.type() == CardType.WILD_DRAW_FOUR)
                && penaltyValue(card) >= penaltyValue(topCard);
    }

    private boolean isWildCard(Card card) {
        if (card == null || card.type() == null) {
            return false;
        }
        return switch (card.type()) {
            case WILD, WILD_DRAW_FOUR, WILD_DRAW_SIX, WILD_DRAW_TEN, WILD_REVERSE_DRAW_FOUR -> true;
            default -> false;
        };
    }

    private boolean isDrawPenaltyCard(Card card) {
        return getDrawPenaltyAmount(card) > 0;
    }

    private int getDrawPenaltyAmount(Card card) {
        if (card == null || card.type() == null) {
            return 0;
        }
        return switch (card.type()) {
            case DRAW_TWO -> 2;
            case DRAW_FOUR, WILD_DRAW_FOUR, WILD_REVERSE_DRAW_FOUR -> 4;
            case WILD_DRAW_SIX -> 6;
            case WILD_DRAW_TEN -> 10;
            default -> 0;
        };
    }

    private boolean requiresColorSelection(Card card) {
        return isWildCard(card);
    }

    private boolean isNoMercyOnlyCard(Card card) {
        if (card == null || card.type() == null) {
            return false;
        }
        return switch (card.type()) {
            case DRAW_FOUR, DISCARD_ALL_COLOR, SKIP_ALL, WILD_DRAW_SIX, WILD_DRAW_TEN, WILD_REVERSE_DRAW_FOUR -> true;
            default -> false;
        };
    }

    private boolean isNoMercy(Game game) {
        return resolveGameMode(game) == GameMode.NO_MERCY;
    }

    private GameMode resolveGameMode(Game game) {
        if (game == null || game.getRoom() == null || game.getRoom().getGameMode() == null) {
            return GameMode.CLASSIC;
        }
        return game.getRoom().getGameMode();
    }

    private void discardAllMatchingColor(Game game, GamePlayer player, CardColor color) {
        if (game == null || player == null || color == null || color == CardColor.WILD) {
            return;
        }

        List<Card> hand = player.getHandCards();
        List<Card> remaining = new ArrayList<>();
        List<Card> discarded = new ArrayList<>();
        for (Card handCard : hand) {
            if (handCard != null && handCard.color() == color) {
                discarded.add(handCard);
            } else {
                remaining.add(handCard);
            }
        }

        if (!discarded.isEmpty()) {
            List<Card> discardPile = fromJson(game.getDiscardPileJson());
            Card dropCard = discardPile.isEmpty() ? null : discardPile.remove(discardPile.size() - 1);
            discardPile.addAll(discarded);
            if (dropCard != null) {
                discardPile.add(dropCard);
            }
            game.setDiscardPileJson(toJson(discardPile));
        }

        setSortedHand(player, remaining);
        player.setSaidUno(remaining.size() == 1);
        gamePlayerRepository.save(player);
        game.setCurrentColor(color);
    }

    private void logPenaltyStack(Card card, Long playerId, Game game) {
        String action = game.getPendingDrawCount() == penaltyValue(card) ? "played" : "stacked";
        log.info("[UNO] penalty {} card={} player={} pendingDrawCount={} currentColor={} nextPlayer={}",
                action,
                formatCard(card),
                playerId,
                game.getPendingDrawCount(),
                game.getCurrentColor(),
                game.getCurrentTurn());
    }

    private void logCanPlay(Card card,
                            Card topCard,
                            CardColor currentColor,
                            Game game,
                            boolean result,
                            String reason) {
        log.info("[UNO-BE] canPlay card={} topCard={} currentColor={} pendingDrawCount={} pendingDrawType={} result={} reason={}",
                formatCard(card),
                formatCard(topCard),
                currentColor,
                game != null ? game.getPendingDrawCount() : 0,
                game != null && game.getPendingDrawType() != null ? game.getPendingDrawType().name() : PendingDrawType.NONE.name(),
                result,
                reason);
    }

    private void clearRematchReady(Game game) {
        for (GamePlayer player : gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)) {
            if (player.isRematchReady()) {
                player.setRematchReady(false);
                gamePlayerRepository.save(player);
            }
        }
    }

    private boolean allPlayersReadyForRematch(Game game) {
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        return !players.isEmpty() && players.stream().allMatch(GamePlayer::isRematchReady);
    }

    private void logUnoPlay(Long playerId,
                            Card card,
                            Card topCard,
                            CardColor currentColor,
                            int pendingDrawCount,
                            boolean isCurrentPlayer,
                            boolean canPlay,
                            String reason,
                            Long nextPlayer) {
        log.info("[UNO-BE] try play player={} card={} topCard={} currentColor={} pendingDrawCount={} isCurrentPlayer={} canPlay={} reason={} nextPlayer={}",
                playerId,
                formatCard(card),
                formatCard(topCard),
                currentColor,
                pendingDrawCount,
                isCurrentPlayer,
                canPlay,
                reason,
                nextPlayer);
    }

    private String formatCard(Card card) {
        if (card == null) {
            return "null";
        }
        String color = card.color() != null ? card.color().name() : "null";
        String type = card.type() != null ? card.type().name() : "null";
        if (card.type() == CardType.NUMBER) {
            return color + "_" + card.value();
        }
        return color + "_" + type;
    }

    private void drawCardsForNextPlayer(Game game, int count) {
        GamePlayer nextPlayer = getNextPlayer(game);
        drawCardsToPlayer(game, nextPlayer, count);
    }

    private void drawCardsToPlayer(Game game, GamePlayer player, int count) {
        Deck deck = getDeck(game);
        List<Card> hand = player.getHandCards();
        for (int i = 0; i < count; i++) {
            Card drawn = deck.drawCard();
            if (drawn != null) {
                hand.add(drawn);
            }
        }
        setSortedHand(player, hand);
        player.setSaidUno(false);
        gamePlayerRepository.save(player);
        saveDeckState(game, deck);
    }

    private void reseatPlayers(List<GamePlayer> players) {
        for (int i = 0; i < players.size(); i++) {
            GamePlayer player = players.get(i);
            player.setSeatIndex(i);
            gamePlayerRepository.save(player);
        }
    }

    boolean shouldStartGame(Room room, int currentPlayerCount) {
        return room != null && currentPlayerCount >= room.getMaxPlayers();
    }

    private void moveToNextPlayer(Game game) {
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        if (players.isEmpty()) {
            return;
        }

        Long currentTurn = game.getCurrentTurn();
        int currentIndex = -1;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUser().getId().equals(currentTurn)) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) {
            game.setCurrentTurn(players.get(0).getUser().getId());
            return;
        }

        int nextIndex = nextSeatIndex(currentIndex, players.size(), game.isClockwise(), 1);
        game.setCurrentTurn(players.get(nextIndex).getUser().getId());
    }

    int nextSeatIndex(int currentIndex, int playerCount, boolean clockwise, int steps) {
        if (playerCount <= 0) {
            return -1;
        }
        int direction = clockwise ? 1 : -1;
        int offset = direction * Math.max(0, steps);
        return Math.floorMod(currentIndex + offset, playerCount);
    }

    Long previewNextTurn(List<Long> orderedPlayerIds, Long currentTurn, boolean clockwise, CardType cardType) {
        if (orderedPlayerIds == null || orderedPlayerIds.isEmpty()) {
            return null;
        }
        int currentIndex = orderedPlayerIds.indexOf(currentTurn);
        if (currentIndex < 0) {
            return orderedPlayerIds.get(0);
        }

        boolean nextClockwise = clockwise;
        int steps = 1;
        if (cardType == CardType.SKIP) {
            steps = 2;
        } else if (cardType == CardType.REVERSE || cardType == CardType.WILD_REVERSE_DRAW_FOUR) {
            nextClockwise = !clockwise;
        } else if (cardType == CardType.SKIP_ALL) {
            steps = 0;
        }

        return orderedPlayerIds.get(nextSeatIndex(currentIndex, orderedPlayerIds.size(), nextClockwise, steps));
    }

    private GamePlayer getNextPlayer(Game game) {
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        if (players.isEmpty()) {
            throw new IllegalStateException("当前游戏没有玩家");
        }

        Long currentTurn = game.getCurrentTurn();
        int currentIndex = -1;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUser().getId().equals(currentTurn)) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) {
            return players.get(0);
        }

        int nextIndex = nextSeatIndex(currentIndex, players.size(), game.isClockwise(), 1);
        return players.get(nextIndex);
    }

    private Card getTopDiscard(Game game) {
        List<Card> discardPile = fromJson(game.getDiscardPileJson());
        if (discardPile.isEmpty()) {
            return null;
        }
        Card top = discardPile.get(discardPile.size() - 1);
        if (top == null || top.color() == null) {
            return new Card(CardColor.RED, CardType.NUMBER, 0);
        }
        return top;
    }

    private Deck getDeck(Game game) {
        List<Card> drawPile = fromJson(game.getDrawPileJson());
        List<Card> discardPile = fromJson(game.getDiscardPileJson());
        return new Deck(drawPile, discardPile, resolveGameMode(game));
    }

    private void saveDeckState(Game game, Deck deck) {
        game.setDrawPileJson(toJson(deck.getDrawPile()));
        game.setDiscardPileJson(toJson(deck.getDiscardPile()));
    }

    private void setSortedHand(GamePlayer player, List<Card> cards) {
        player.setHandCards(sortHandCards(cards));
    }

    private List<Card> sortHandCards(List<Card> cards) {
        List<Card> sorted = new ArrayList<>(cards == null ? List.of() : cards);
        sorted.sort(handCardComparator());
        return sorted;
    }

    private Comparator<Card> handCardComparator() {
        return Comparator
                .comparingInt(this::globalCardGroup)
                .thenComparingInt(this::colorRank)
                .thenComparingInt(this::typeRankWithinColor)
                .thenComparingInt(Card::value);
    }

    private int globalCardGroup(Card card) {
        if (card == null || card.type() == null) {
            return 99;
        }
        if (isWildCard(card)) {
            return 0;
        }
        return 1;
    }

    private int colorRank(Card card) {
        if (card == null || card.color() == null || card.color() == CardColor.WILD) {
            return -1;
        }
        return switch (card.color()) {
            case RED -> 0;
            case YELLOW -> 1;
            case GREEN -> 2;
            case BLUE -> 3;
            default -> 9;
        };
    }

    private int typeRankWithinColor(Card card) {
        if (card == null || card.type() == null) {
            return 99;
        }
        return switch (card.type()) {
            case WILD -> 0;
            case WILD_DRAW_FOUR -> 1;
            case WILD_DRAW_SIX -> 2;
            case WILD_DRAW_TEN -> 3;
            case WILD_REVERSE_DRAW_FOUR -> 4;
            case DRAW_TWO -> 5;
            case DRAW_FOUR -> 6;
            case DISCARD_ALL_COLOR -> 7;
            case SKIP_ALL -> 8;
            case SKIP -> 9;
            case REVERSE -> 10;
            case NUMBER -> 20;
        };
    }

    private PublicGamePatch buildPublicGamePatch(Game game,
                                                 List<GamePlayer> players,
                                                 String type,
                                                 Long actorUserId,
                                                 String actorName,
                                                 String message) {
        long startedAt = System.nanoTime();
        try {
            Card topCard = getTopDiscard(game);
            CardColor effectiveColor = resolveCurrentColor(game.getCurrentColor(), topCard);
            Long currentPlayerId = game.getCurrentTurn();
            Integer currentPlayerIndex = resolveCurrentPlayerIndex(game);
            String currentPlayerName = null;
            Long winnerId = null;
            List<Long> rematchReadyPlayerIds = new ArrayList<>();
            List<PublicPlayerInfo> publicPlayers = new ArrayList<>();
            for (GamePlayer player : players) {
                boolean currentPlayer = player.getUser().getId().equals(currentPlayerId);
                if (currentPlayer) {
                    currentPlayerName = player.getUser().getUsername();
                }
                if (game.getStatus() == GameStatus.FINISHED && player.getHandCards().isEmpty()) {
                    winnerId = player.getUser().getId();
                }
                if (player.isRematchReady()) {
                    rematchReadyPlayerIds.add(player.getUser().getId());
                }
                publicPlayers.add(new PublicPlayerInfo(
                        player.getUser().getId(),
                        player.getUser().getUsername(),
                        player.getHandCards() != null ? player.getHandCards().size() : 0,
                        player.getSeatIndex(),
                        player.isSaidUno(),
                        currentPlayer,
                        player.isRematchReady()
                ));
            }
            return new PublicGamePatch(
                    type,
                    game.getRoom().getId(),
                    game.getId(),
                    getCurrentStateVersion(game.getRoom().getId(), game),
                    System.currentTimeMillis(),
                    actorUserId,
                    actorName,
                    currentPlayerId,
                    currentPlayerName,
                    currentPlayerIndex,
                    effectiveColor.name(),
                    game.isClockwise() ? 1 : -1,
                    topCard != null ? cardToMap(topCard) : null,
                    topCard != null ? cardToMap(topCard) : null,
                    game.getPendingDrawCount(),
                    game.getPendingDrawType() != null ? game.getPendingDrawType().name() : PendingDrawType.NONE.name(),
                    game.getLastPenaltyPlayerId(),
                    fromJson(game.getDrawPileJson()).size(),
                    game.getStatus().name(),
                    game.getRoom().getStatus().name(),
                    publicPlayers,
                    winnerId,
                    rematchReadyPlayerIds,
                    message,
                    false
            );
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=buildPublicGamePatch roomId={} gameId={} type={} costMs={}",
                    game.getRoom().getId(),
                    game.getId(),
                    type,
                    costMs);
        }
    }

    private void sendPrivateHandPatch(Game game, GamePlayer player, String type) {
        if (game == null || player == null) {
            return;
        }
        sendPrivateHandPatch(buildPrivateHandPatchDelivery(game, player, type));
    }

    private PrivateHandPatchDelivery buildPrivateHandPatchDelivery(Game game, GamePlayer player, String type) {
        return new PrivateHandPatchDelivery(
                player.getUser().getUsername(),
                game.getRoom().getId(),
                game.getId(),
                player.getUser().getId(),
                buildPrivateHandPatch(game, player, type)
        );
    }

    private void sendPrivateHandPatch(PrivateHandPatchDelivery delivery) {
        long startedAt = System.nanoTime();
        try {
            wsService.sendPrivateHandPatch(
                    delivery.username(),
                    delivery.roomId(),
                    delivery.gameId(),
                    delivery.userId(),
                    delivery.patch()
            );
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=sendPrivateHandPatch roomId={} gameId={} userId={} costMs={}",
                    delivery.roomId(),
                    delivery.gameId(),
                    delivery.userId(),
                    costMs);
        }
    }

    private void sendPrivateHandPatches(Game game, List<GamePlayer> players, String type) {
        for (GamePlayer player : players) {
            sendPrivateHandPatch(game, player, type);
        }
    }

    private PrivateHandPatch buildPrivateHandPatch(Game game, GamePlayer player, String type) {
        long startedAt = System.nanoTime();
        try {
            long version = getCurrentStateVersion(game.getRoom().getId(), game);
            return new PrivateHandPatch(
                    type,
                    game.getRoom().getId(),
                    game.getId(),
                    version,
                    System.currentTimeMillis(),
                    player.getUser().getId(),
                    buildHandPatchId(game.getRoom().getId(), game.getId(), player.getUser().getId(), version, type),
                    mapCards(sortHandCards(player.getHandCards())),
                    null,
                    hasPendingDraw(game) && game.getCurrentTurn() != null && game.getCurrentTurn().equals(player.getUser().getId()),
                    game.getPendingDrawCount()
            );
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=buildPrivateHandPatch roomId={} gameId={} userId={} type={} costMs={}",
                    game.getRoom().getId(),
                    game.getId(),
                    player.getUser().getId(),
                    type,
                    costMs);
        }
    }

    private String buildHandPatchId(Long roomId, Long gameId, Long userId, long version, String type) {
        return roomId + "-" + gameId + "-" + userId + "-" + version + "-" + type;
    }

    private List<Map<String, Object>> mapCards(List<Card> cards) {
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Card card : cards) {
            mapped.add(cardToMap(card));
        }
        return mapped;
    }

    private void publishAfterCommit(String action, Runnable publication) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            publication.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publication.run();
                } catch (RuntimeException ex) {
                    log.error("[SYNC] action={} phase=afterCommit websocketPublishFailed=true", action, ex);
                }
            }
        });
    }

    private Map<String, Object> operationAck(Long roomId, Long gameId, long version, String type) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", type);
        ack.put("roomId", roomId);
        ack.put("gameId", gameId);
        ack.put("version", version);
        ack.put("accepted", true);
        return ack;
    }

    private Map<String, Object> getRoomStateWithVersion(Room room, Game game) {
        Map<String, Object> roomState = roomService.getRoomState(room);
        roomState.put("version", getCurrentStateVersion(room.getId(), game));
        return roomState;
    }

    long getCurrentStateVersion(Long roomId, Game game) {
        if (roomId == null) {
            return System.currentTimeMillis();
        }
        AtomicLong counter = roomStateVersions.computeIfAbsent(roomId, ignored -> new AtomicLong(seedStateVersion(game)));
        return counter.get();
    }

    private long bumpStateVersion(Long roomId) {
        AtomicLong counter = roomStateVersions.computeIfAbsent(roomId, ignored -> new AtomicLong(System.currentTimeMillis()));
        return counter.updateAndGet(current -> Math.max(System.currentTimeMillis(), current + 1));
    }

    private long seedStateVersion(Game game) {
        if (game != null && game.getCreatedAt() != null) {
            return game.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int resolveCurrentPlayerIndex(Game game) {
        if (game == null || game.getCurrentTurn() == null) {
            return -1;
        }
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUser().getId().equals(game.getCurrentTurn())) {
                return i;
            }
        }
        return -1;
    }

    private Long getRoomIdByGameId(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        return game.getRoom().getId();
    }

    private <T> T withRoomLock(Long roomId, Supplier<T> action) {
        ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON serialize error", e);
            return "[]";
        }
    }

    private List<Card> fromJson(String json) {
        try {
            if (json == null || json.isEmpty() || "null".equals(json)) {
                return new ArrayList<>();
            }

            List<Card> cards = OBJECT_MAPPER.readValue(
                    json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Card>>() {}
            );

            List<Card> validCards = new ArrayList<>();
            for (Card card : cards) {
                if (card != null && card.color() != null && card.type() != null) {
                    validCards.add(card);
                }
            }
            return validCards;
        } catch (Exception e) {
            log.error("JSON deserialize error: json={}", json, e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> cardToMap(Card card) {
        Map<String, Object> map = new HashMap<>();
        if (card == null) {
            map.put("color", "RED");
            map.put("type", "NUMBER");
            map.put("value", 0);
            return map;
        }

        map.put("color", card.color() != null ? card.color().name() : "RED");
        map.put("type", card.type() != null ? card.type().name() : "NUMBER");
        map.put("value", card.value());
        return map;
    }
}
