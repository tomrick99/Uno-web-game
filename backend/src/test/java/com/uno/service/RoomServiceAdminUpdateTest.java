package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.RoomStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomServiceAdminUpdateTest {

    private final RoomRepository roomRepository = mock(RoomRepository.class);
    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
    private final RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

    @Test
    void waitingRoomCanBeUpdated() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of());

        roomService.updateRoomConfigByAdmin(1L, 6, 16, 15, GameMode.NO_MERCY);

        assertEquals(6, room.getMaxPlayers());
        assertEquals(16, room.getTotalRounds());
        assertEquals(15, room.getRoundTimeLimitMinutes());
        assertEquals(GameMode.NO_MERCY, room.getGameMode());
    }

    @Test
    void updateRejectsMaxPlayersBelowCurrentPlayerCount() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        Game game = new Game();
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(new GamePlayer(), new GamePlayer(), new GamePlayer()));

        assertThrows(IllegalArgumentException.class,
                () -> roomService.updateRoomConfigByAdmin(1L, 2, 8, 10, GameMode.CLASSIC));
    }

    @Test
    void playingRoomCannotBeUpdated() {
        Room room = room(1L, RoomStatus.PLAYING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));

        assertThrows(IllegalArgumentException.class,
                () -> roomService.updateRoomConfigByAdmin(1L, 4, 16, 10, GameMode.NO_MERCY));
    }

    private Room room(Long id, RoomStatus status, int maxPlayers) {
        User host = new User("admin", "pw");
        host.setId(10L);
        Room room = new Room();
        room.setId(id);
        room.setRoomCode("ABC123");
        room.setHost(host);
        room.setStatus(status);
        room.setMaxPlayers(maxPlayers);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        return room;
    }
}
