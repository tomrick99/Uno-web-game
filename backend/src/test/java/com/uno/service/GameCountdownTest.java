package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameEndReason;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.RoomStatus;
import com.uno.model.Card;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameCountdownTest {

    private GameRepository gameRepository;
    private GamePlayerRepository gamePlayerRepository;
    private RoomService roomService;
    private GameWebSocketService wsService;
    private GameService gameService;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gamePlayerRepository = mock(GamePlayerRepository.class);
        roomService = mock(RoomService.class);
        wsService = mock(GameWebSocketService.class);
        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                mock(RoomRepository.class),
                mock(UserRepository.class),
                wsService,
                roomService);
    }

    @Test
    void expiredCountdownFinishesGameAndRanksByFewestCards() {
        Room room = room(true);
        Game game = game(room, LocalDateTime.now().minusSeconds(1));
        GamePlayer alice = player(game, 1L, "alice", 0, 3);
        GamePlayer bob = player(game, 2L, "bob", 1, 1);
        List<GamePlayer> players = List.of(alice, bob);

        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(roomService.getRoomState(room)).thenReturn(new LinkedHashMap<>(java.util.Map.of(
                "roomId", room.getId(),
                "status", RoomStatus.CLOSED.name())));
        doAnswer(invocation -> {
            room.setStatus(RoomStatus.CLOSED);
            return null;
        }).when(roomService).closeRoom(room);

        assertTrue(gameService.expireCountdown(game.getId()));

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals(GameEndReason.COUNTDOWN_EXPIRED, game.getEndReason());
        assertEquals(bob.getUser().getId(), game.getWinnerId());
        assertNull(game.getCurrentTurn());
        assertEquals(RoomStatus.CLOSED, room.getStatus());
        verify(wsService).broadcastRoomState(any(), eq("GAME_FINISHED"), any());
        verify(wsService).broadcastPublicGamePatch(any());
    }

    @Test
    void disabledCountdownDoesNotFinishAnOtherwiseExpiredGame() {
        Room room = room(false);
        Game game = game(room, LocalDateTime.now().minusMinutes(1));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));

        assertFalse(gameService.expireCountdown(game.getId()));

        assertEquals(GameStatus.PLAYING, game.getStatus());
        assertNull(game.getWinnerId());
        assertNull(game.getEndReason());
    }

    private Room room(boolean countdownEnabled) {
        User host = new User("host", "pw");
        host.setId(10L);
        Room room = new Room();
        room.setId(11L);
        room.setRoomCode("ABC123");
        room.setHost(host);
        room.setStatus(RoomStatus.PLAYING);
        room.setCountdownEnabled(countdownEnabled);
        room.setRoundTimeLimitMinutes(10);
        return room;
    }

    private Game game(Room room, LocalDateTime countdownEndsAt) {
        Game game = new Game();
        game.setId(21L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(1L);
        game.setCurrentColor(CardColor.RED);
        game.setCountdownEndsAt(countdownEndsAt);
        game.setDrawPileJson("[]");
        game.setDiscardPileJson("[]");
        return game;
    }

    private GamePlayer player(Game game, Long userId, String username, int seatIndex, int cardCount) {
        User user = new User(username, "pw");
        user.setId(userId);
        List<Card> cards = new ArrayList<>();
        for (int index = 0; index < cardCount; index++) {
            cards.add(new Card(CardColor.RED, CardType.NUMBER, index));
        }
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(cards);
        return player;
    }
}
