package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameStatus;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
@Transactional
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private record PlayValidation(boolean playable, String reason) {}

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

    public Card playCard(Long gameId, Long userId, int cardIndex, CardColor chosenColor) {
        Long roomId = getRoomIdByGameId(gameId);
        return withRoomLock(roomId, () -> doPlayCard(gameId, userId, cardIndex, chosenColor));
    }

    public Card drawCard(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        return withRoomLock(roomId, () -> doDrawCard(gameId, userId));
    }

    public Map<String, Object> drawPenalty(Long gameId, Long userId) {
        Long roomId = getRoomIdByGameId(gameId);
        return withRoomLock(roomId, () -> doDrawPenalty(gameId, userId));
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
                    .orElseThrow(() -> new IllegalArgumentException("房间不存在"));

            Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
            Long gameId = gameOpt.map(Game::getId).orElse(null);
            String message = "房间已被管理员删除";
            if (operatorName != null && !operatorName.isBlank()) {
                message = message + "（" + operatorName + "）";
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
                .orElseThrow(() -> new IllegalArgumentException("房间不存在: " + roomId));
        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));
        return getGameState(game.getId());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerHandByRoomId(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("房间不存在: " + roomId));
        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));
        return getPlayerHand(game.getId(), userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGameState(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("gameId", game.getId());
        state.put("roomId", game.getRoom().getId());
        state.put("roomCode", game.getRoom().getRoomCode());
        state.put("roomStatus", game.getRoom().getStatus().name());
        state.put("maxPlayers", game.getRoom().getMaxPlayers());
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
        return state;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerHand(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));
        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("玩家不在当前游戏中"));

        List<Card> hand = sortHandCards(player.getHandCards());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card card : hand) {
            result.add(cardToMap(card));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildRealtimeSnapshot(Long roomId, Long gameId, Long userId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("roomState", roomService.getRoomState(roomId));
        snapshot.put("gameState", gameId != null ? getGameState(gameId) : null);
        snapshot.put("handCards", gameId != null ? getPlayerHand(gameId, userId) : new ArrayList<>());
        return snapshot;
    }

    private Map<String, Object> doJoinGame(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("房间不存在: " + roomId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        Game game = gameRepository.findByRoom(room).stream()
                .findFirst()
                .orElseGet(() -> createWaitingGame(room));

        Optional<GamePlayer> existingPlayer = gamePlayerRepository.findByGameAndUser(game, user);
        if (existingPlayer.isEmpty()) {
            if (room.getStatus() != RoomStatus.WAITING || game.getStatus() == GameStatus.FINISHED) {
                throw new IllegalArgumentException("房间已开始或已结束，无法加入");
            }

            int playerCount = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game).size();
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
            log.info("[UNO] room joined roomId={} players={}", roomId, playerCount + 1);

            wsService.broadcastRoomState(roomService.getRoomState(room), "PLAYER_JOINED", user.getUsername() + " 加入了房间");
        }

        int currentPlayerCount = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game).size();
        if (currentPlayerCount >= 2 && game.getStatus() == GameStatus.WAITING) {
            startGame(game);
            room.setStatus(RoomStatus.PLAYING);
            roomRepository.save(room);

            wsService.broadcastRoomState(roomService.getRoomState(room), "GAME_STARTED", "游戏开始");
            wsService.broadcastGameState(getGameState(game.getId()), "GAME_STARTED", "已发牌，轮到首位玩家");
            broadcastHandsForGame(game, "HAND_SYNC");
        } else if (game.getStatus() == GameStatus.PLAYING || game.getStatus() == GameStatus.FINISHED) {
            wsService.broadcastRoomState(roomService.getRoomState(room), "PLAYER_SYNC", null);
        }

        return buildRealtimeSnapshot(room.getId(), game.getId(), userId);
    }

    private Card doPlayCard(Long gameId, Long userId, int cardIndex, CardColor chosenColor) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));

        if (game.getStatus() != GameStatus.PLAYING) {
            throw new IllegalArgumentException("当前游戏未开始或已结束");
        }
        if (!userId.equals(game.getCurrentTurn())) {
            logUnoPlay(userId, null, getTopDiscard(game), game.getCurrentColor(), game.getPendingDrawCount(), false, false,
                    "not current player", game.getCurrentTurn());
            throw new IllegalArgumentException("不是当前玩家，不能出牌");
        }

        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("玩家不在当前游戏中"));

        List<Card> hand = sortHandCards(player.getHandCards());
        if (cardIndex < 0 || cardIndex >= hand.size()) {
            throw new IllegalArgumentException("无效的卡牌索引");
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
        if ((card.type() == CardType.WILD || card.type() == CardType.WILD_DRAW_FOUR)
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

        boolean handledTurnAdvance = processCardEffect(game, card, chosenColor, userId);
        if (!handledTurnAdvance) {
            moveToNextPlayer(game);
        }
        gameRepository.save(game);
        logUnoPlay(userId, card, topCard, effectiveColor, game.getPendingDrawCount(), true, true, "accepted", game.getCurrentTurn());

        if (hand.isEmpty()) {
            game.setStatus(GameStatus.FINISHED);
            gameRepository.save(game);
            roomService.closeRoom(game.getRoom());
            Map<String, Object> finishedGameState = getGameState(game.getId());
            Map<String, Object> finishedRoomState = roomService.getRoomState(game.getRoom());
            int playerCount = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game).size();
            log.info("[UNO] game finished gameId={} roomId={} winner={}", game.getId(), game.getRoom().getId(), userId);
            log.info("[UNO] broadcasting FINISHED to /topic/games/{}", game.getId());
            log.info("[UNO] broadcasting room update to /topic/rooms/{}", game.getRoom().getId());
            log.info("[UNO] broadcast game finished gameId={} roomId={} players={}", game.getId(), game.getRoom().getId(), playerCount);

            wsService.broadcastRoomState(roomService.getRoomState(game.getRoom()), "GAME_FINISHED", user.getUsername() + " 赢得了本局");
            wsService.broadcastGameState(finishedGameState, "GAME_FINISHED", user.getUsername() + " wins!");
            broadcastHandsForGame(game, "HAND_SYNC");
        } else {
            String display = card.type() == CardType.NUMBER ? String.valueOf(card.value()) : card.type().name();
            wsService.broadcastGameState(getGameState(game.getId()), "CARD_PLAYED", user.getUsername() + " 打出了 " + display);
            broadcastHandsForGame(game, "HAND_SYNC");
        }

        return card;
    }

    private Card doDrawCard(Long gameId, Long userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));

        if (game.getStatus() != GameStatus.PLAYING) {
            throw new IllegalArgumentException("当前游戏未开始或已结束");
        }
        if (!userId.equals(game.getCurrentTurn())) {
            throw new IllegalArgumentException("不是当前玩家，不能抽牌");
        }

        User user = userRepository.getReferenceById(userId);
        GamePlayer player = gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("玩家不在当前游戏中"));

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

        wsService.broadcastGameState(getGameState(game.getId()), "CARD_DRAWN", user.getUsername() + " 抽了一张牌");
        broadcastHandsForGame(game, "HAND_SYNC");
        return drawn;
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

        Long nextTurn = game.getLastPenaltyPlayerId();
        clearPendingDraw(game);
        if (nextTurn != null) {
            game.setCurrentTurn(nextTurn);
        }
        gameRepository.save(game);

        log.info("[UNO] penalty drawn player={} drawCount={} nextTurn={}", userId, drawCount, game.getCurrentTurn());
        wsService.broadcastGameState(getGameState(game.getId()), "PENALTY_DRAWN", user.getUsername() + " drew " + drawCount + " penalty cards");
        broadcastHandsForGame(game, "HAND_SYNC");
        return buildRealtimeSnapshot(game.getRoom().getId(), game.getId(), userId);
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
        }

        if (allPlayersReadyForRematch(game)) {
            return doRestartGame(gameId, userId, true);
        }

        wsService.broadcastGameState(getGameState(game.getId()), "REMATCH_READY",
                user.getUsername() + " is ready for a rematch. Waiting for the other player.");
        return buildRealtimeSnapshot(game.getRoom().getId(), game.getId(), userId);
    }

    private Map<String, Object> doRestartGame(Long gameId, Long userId) {
        return doRestartGame(gameId, userId, false);
    }

    private Map<String, Object> doRestartGame(Long gameId, Long userId, boolean rematchApproved) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));
        User user = userRepository.getReferenceById(userId);
        gamePlayerRepository.findByGameAndUser(game, user)
                .orElseThrow(() -> new IllegalArgumentException("只有本局玩家可以重新开始"));

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

        wsService.broadcastRoomState(roomService.getRoomState(room), "GAME_RESTARTED", "再来一局开始");
        wsService.broadcastGameState(getGameState(game.getId()), "GAME_RESTARTED", "已重新洗牌并发牌");
        broadcastHandsForGame(game, "HAND_SYNC");

        return buildRealtimeSnapshot(room.getId(), game.getId(), userId);
    }

    private Map<String, Object> doLeaveRoom(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
        if (gameOpt.isEmpty()) {
            if (room.getHost().getId().equals(userId)) {
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
            log.info("[UNO] room closed or updated roomId={} status={}", roomId, room.getStatus());
            log.info("[UNO] broadcasting PLAYER_LEFT roomId={}", roomId);
            wsService.broadcastRoomState(roomService.getRoomState(room), "PLAYER_LEFT", "Opponent left the room");
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

        log.info("[UNO] room closed or updated roomId={} status={}", roomId, room.getStatus());
        log.info("[UNO] broadcasting PLAYER_LEFT roomId={}", roomId);
        wsService.broadcastRoomState(roomService.getRoomState(room), "PLAYER_LEFT", user.getUsername() + " left the room");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomClosed", false);
        result.put("message", "Left room");
        result.put("roomState", roomService.getRoomState(room));
        return result;
    }

    private Game createWaitingGame(Room room) {
        Game game = new Game();
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        game.setClockwise(true);
        return gameRepository.save(game);
    }

    private void startGame(Game game) {
        Deck deck = new Deck();
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
            throw new IllegalStateException("至少需要两名玩家才能开始");
        }

        for (GamePlayer player : players) {
            List<Card> hand = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Card card = deck.drawCard();
                if (card == null) {
                    throw new RuntimeException("牌堆已空，无法发牌");
                }
                hand.add(card);
            }
            setSortedHand(player, hand);
            player.setSaidUno(false);
            player.setRematchReady(false);
            gamePlayerRepository.save(player);
        }

        Card firstDiscard = deck.drawCard();
        if (firstDiscard == null || firstDiscard.color() == null || firstDiscard.color() == CardColor.WILD) {
            firstDiscard = new Card(CardColor.RED, CardType.NUMBER, 0);
        }
        deck.discard(firstDiscard);

        saveDeckState(game, deck);
        game.setCurrentColor(firstDiscard.color());
        game.setCurrentTurn(players.get(0).getUser().getId());
    }

    private PlayValidation validatePlay(Game game, Card card, Card topCard, CardColor currentColor) {
        if (card == null || card.color() == null || card.type() == null) {
            return new PlayValidation(false, "invalid card");
        }
        if (hasPendingDraw(game)) {
            if (isWildDrawFourChain(game)) {
                if (card.type() == CardType.WILD_DRAW_FOUR) {
                    logCanPlay(card, topCard, currentColor, game, true, "pending +4 chain allows +4");
                    return new PlayValidation(true, "pending +4 chain allows +4");
                }
                logCanPlay(card, topCard, currentColor, game, false, "pending +4 chain only allows +4");
                return new PlayValidation(false, "pending +4 chain only allows +4");
            }

            if (card.type() == CardType.DRAW_TWO) {
                logCanPlay(card, topCard, currentColor, game, true, "pending +2 chain allows +2");
                return new PlayValidation(true, "pending +2 chain allows +2");
            }
            if (card.type() == CardType.WILD_DRAW_FOUR) {
                logCanPlay(card, topCard, currentColor, game, true, "pending +2 chain allows +4");
                return new PlayValidation(true, "pending +2 chain allows +4");
            }
            logCanPlay(card, topCard, currentColor, game, false, "pending +2 chain only allows +2 or +4");
            return new PlayValidation(false, "pending +2 chain only allows +2 or +4");
        }

        if (card.type() == CardType.WILD) {
            logCanPlay(card, topCard, currentColor, game, true, "wild card");
            return new PlayValidation(true, "wild card");
        }
        if (card.type() == CardType.WILD_DRAW_FOUR) {
            logCanPlay(card, topCard, currentColor, game, true, "wild draw four");
            return new PlayValidation(true, "wild draw four");
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

    private boolean processCardEffect(Game game, Card card, CardColor chosenColor, Long playerId) {
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
            case WILD_DRAW_FOUR:
                if (chosenColor == null) {
                    throw new IllegalArgumentException("万能 +4 必须选择颜色");
                }
                game.setCurrentColor(chosenColor);
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
            throw new IllegalArgumentException("鏃犳晥鐨勫崱鐗?");
        }
        if (card.type() != CardType.WILD && card.type() != CardType.WILD_DRAW_FOUR) {
            return;
        }
        if (chosenColor == null || chosenColor == CardColor.WILD) {
            throw new IllegalArgumentException("涓囪兘鐗屽繀椤婚€夋嫨 RED / YELLOW / GREEN / BLUE");
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

    private boolean isWildDrawFourChain(Game game) {
        return game != null && game.getPendingDrawType() == PendingDrawType.WILD_DRAW_FOUR_CHAIN;
    }

    private void addPendingDraw(Game game, Long playerId, Card card, CardColor chosenColor) {
        if (card == null || card.type() == null) {
            return;
        }

        if (card.type() == CardType.DRAW_TWO) {
            game.setPendingDrawType(PendingDrawType.DRAW_TWO_CHAIN);
            game.setPendingDrawCount(game.getPendingDrawCount() + 2);
        } else if (card.type() == CardType.WILD_DRAW_FOUR) {
            game.setPendingDrawType(PendingDrawType.WILD_DRAW_FOUR_CHAIN);
            game.setPendingDrawCount(game.getPendingDrawCount() + 4);
            if (chosenColor != null && chosenColor != CardColor.WILD) {
                game.setCurrentColor(chosenColor);
            }
        }

        game.setLastPenaltyPlayerId(playerId);
        logPenaltyStack(card, playerId, game);
    }

    private boolean isPenaltyStackCard(Card card) {
        return card != null && (card.type() == CardType.DRAW_TWO || card.type() == CardType.WILD_DRAW_FOUR);
    }

    private int penaltyValue(Card card) {
        if (card == null || card.type() == null) {
            return 0;
        }
        return switch (card.type()) {
            case DRAW_TWO -> 2;
            case WILD_DRAW_FOUR -> 4;
            default -> 0;
        };
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

        int nextIndex = game.isClockwise()
                ? (currentIndex + 1) % players.size()
                : (currentIndex - 1 + players.size()) % players.size();
        game.setCurrentTurn(players.get(nextIndex).getUser().getId());
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

        int nextIndex = game.isClockwise()
                ? (currentIndex + 1) % players.size()
                : (currentIndex - 1 + players.size()) % players.size();
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
        if (drawPile.isEmpty()) {
            Deck newDeck = new Deck();
            game.setDrawPileJson(toJson(newDeck.getDrawPile()));
            game.setDiscardPileJson(toJson(newDeck.getDiscardPile()));
            gameRepository.save(game);
            return newDeck;
        }

        Deck deck = new Deck(drawPile, discardPile);
        if (deck.getDrawPileSize() != drawPile.size()) {
            game.setDrawPileJson(toJson(deck.getDrawPile()));
            game.setDiscardPileJson(toJson(deck.getDiscardPile()));
            gameRepository.save(game);
        }
        return deck;
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
        if (card.type() == CardType.WILD || card.type() == CardType.WILD_DRAW_FOUR) {
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
            case DRAW_TWO -> 2;
            case SKIP -> 3;
            case REVERSE -> 4;
            case NUMBER -> 10;
        };
    }

    private void broadcastHandsForGame(Game game, String event) {
        for (GamePlayer gp : gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)) {
            wsService.broadcastPlayerHand(game.getId(), gp.getUser().getId(), getPlayerHand(game.getId(), gp.getUser().getId()), event);
        }
    }

    private Long getRoomIdByGameId(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("游戏不存在"));
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
