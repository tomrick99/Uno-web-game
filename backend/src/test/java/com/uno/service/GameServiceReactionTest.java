package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private final RoomRepository roomRepository = mock(RoomRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GameWebSocketService wsService = mock(GameWebSocketService.class);
    private final RoomService roomService = mock(RoomService.class);
    private GameService gameService;

    @BeforeEach
    void setUp() {
        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);
    }

    @Test
    void roomPlayerCanBroadcastAllowedReactionWithoutPersistence() {
        Room room = new Room();
        room.setId(12L);
        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        User user = new User("alice", "unused");
        user.setId(99L);
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        Map<String, Object> broadcast = Map.of("type", "PLAYER_REACTION", "emoji", "👍");

        when(roomRepository.findById(12L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(userRepository.findById(99L)).thenReturn(Optional.of(user));
        when(gamePlayerRepository.findByGameAndUser(game, user)).thenReturn(Optional.of(player));
        when(wsService.broadcastPlayerReaction(12L, 99L, "alice", "👍")).thenReturn(broadcast);

        assertEquals(broadcast, gameService.sendReaction(12L, 99L, " 👍 "));
        verify(wsService).broadcastPlayerReaction(12L, 99L, "alice", "👍");
        verify(gamePlayerRepository, never()).save(player);
    }

    @Test
    void unsupportedReactionIsRejectedBeforeRoomLookup() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> gameService.sendReaction(12L, 99L, "hello"));

        assertEquals("Unsupported reaction", error.getMessage());
        verify(roomRepository, never()).findById(12L);
        verify(wsService, never()).broadcastPlayerReaction(12L, 99L, "alice", "hello");
    }

    @Test
    void userOutsideRoomCannotBroadcastReaction() {
        Room room = new Room();
        room.setId(12L);
        Game game = new Game();
        game.setRoom(room);
        User user = new User("mallory", "unused");
        user.setId(101L);

        when(roomRepository.findById(12L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(userRepository.findById(101L)).thenReturn(Optional.of(user));
        when(gamePlayerRepository.findByGameAndUser(game, user)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> gameService.sendReaction(12L, 101L, "😂"));
        verify(wsService, never()).broadcastPlayerReaction(12L, 101L, "mallory", "😂");
    }
}
