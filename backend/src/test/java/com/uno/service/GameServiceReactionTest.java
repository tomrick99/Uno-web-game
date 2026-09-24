package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceReactionTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GameWebSocketService webSocketService = mock(GameWebSocketService.class);
    private GameService gameService;

    private Game game;
    private User alice;

    @BeforeEach
    void setUp() {
        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                mock(RoomRepository.class),
                userRepository,
                webSocketService,
                mock(RoomService.class));

        Room room = new Room();
        room.setId(12L);
        game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);

        alice = new User("alice", "password");
        alice.setId(7L);
    }

    @Test
    void broadcastsAllowedReactionForAuthenticatedGamePlayer() {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(alice);
        Map<String, Object> payload = Map.of("type", "PLAYER_REACTION", "emoji", "🎉");

        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(userRepository.findById(7L)).thenReturn(Optional.of(alice));
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(player));
        when(webSocketService.broadcastPlayerReaction(12L, 20L, 7L, "alice", "🎉"))
                .thenReturn(payload);

        assertEquals(payload, gameService.sendReaction(20L, 7L, "🎉"));
        verify(webSocketService).broadcastPlayerReaction(12L, 20L, 7L, "alice", "🎉");
    }

    @Test
    void rejectsUnsupportedReactionBeforeReadingGameState() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> gameService.sendReaction(20L, 7L, "not-an-emoji"));

        assertEquals("Unsupported reaction", error.getMessage());
        verify(gameRepository, never()).findById(20L);
        verify(webSocketService, never()).broadcastPlayerReaction(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsUserWhoIsNotAPlayerInTheGame() {
        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(userRepository.findById(7L)).thenReturn(Optional.of(alice));
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> gameService.sendReaction(20L, 7L, "👍"));

        assertEquals("Only players in this game can react", error.getMessage());
        verify(webSocketService, never()).broadcastPlayerReaction(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
