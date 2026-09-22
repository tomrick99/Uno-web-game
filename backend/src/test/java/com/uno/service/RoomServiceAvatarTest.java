package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.RoomStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomServiceAvatarTest {

    @Test
    void roomStateKeepsEachAvatarBoundToItsUserId() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomService service = new RoomService(roomRepository, gameRepository, playerRepository);

        User alice = userWithAvatar(11L, "alice", LocalDateTime.of(2026, 1, 1, 10, 0));
        User bob = userWithAvatar(22L, "bob", LocalDateTime.of(2026, 1, 1, 11, 0));
        Room room = new Room();
        room.setId(3L);
        room.setRoomCode("ABC123");
        room.setHost(alice);
        room.setStatus(RoomStatus.WAITING);
        Game game = new Game();
        game.setId(4L);
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        GamePlayer alicePlayer = gamePlayer(game, alice, 0);
        GamePlayer bobPlayer = gamePlayer(game, bob, 1);
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(alicePlayer, bobPlayer));

        Map<String, Object> state = service.getRoomState(room);

        @SuppressWarnings("unchecked")
        Map<String, Object> host = (Map<String, Object>) state.get("host");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        assertEquals(alice.getAvatarUrl(), host.get("avatarUrl"));
        assertEquals(11L, players.get(0).get("userId"));
        assertEquals(alice.getAvatarUrl(), players.get(0).get("avatarUrl"));
        assertEquals(22L, players.get(1).get("userId"));
        assertEquals(bob.getAvatarUrl(), players.get(1).get("avatarUrl"));
        assertNotEquals(players.get(0).get("avatarUrl"), players.get(1).get("avatarUrl"));
    }

    private User userWithAvatar(Long id, String username, LocalDateTime updatedAt) {
        User user = new User(username, "pw");
        user.setId(id);
        user.setAvatarData(new byte[]{1});
        user.setAvatarContentType("image/jpeg");
        user.setAvatarUpdatedAt(updatedAt);
        return user;
    }

    private GamePlayer gamePlayer(Game game, User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(List.of());
        return player;
    }
}
