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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class RoomService {

    private final RoomRepository roomRepository;
    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;

    public RoomService(RoomRepository roomRepository,
                       GameRepository gameRepository,
                       GamePlayerRepository gamePlayerRepository) {
        this.roomRepository = roomRepository;
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
    }

    public Room createRoom(User host, int maxPlayers, int totalRounds, int roundTimeLimitMinutes, GameMode gameMode) {
        validateRoomConfig(maxPlayers, totalRounds, roundTimeLimitMinutes, gameMode);
        Room room = new Room();
        room.setHost(host);
        room.setMaxPlayers(maxPlayers);
        room.setTotalRounds(totalRounds);
        room.setRoundTimeLimitMinutes(roundTimeLimitMinutes);
        room.setGameMode(gameMode == null ? GameMode.CLASSIC : gameMode);
        room.setStatus(RoomStatus.WAITING);
        return roomRepository.save(room);
    }

    private void validateRoomConfig(int maxPlayers, int totalRounds, int roundTimeLimitMinutes, GameMode gameMode) {
        if (maxPlayers < 2 || maxPlayers > 8) {
            throw new IllegalArgumentException("Players must be between 2 and 8");
        }
        if (totalRounds != 8 && totalRounds != 16 && totalRounds != 32) {
            throw new IllegalArgumentException("Total rounds must be 8, 16, or 32");
        }
        if (roundTimeLimitMinutes != 5 && roundTimeLimitMinutes != 10 && roundTimeLimitMinutes != 15) {
            throw new IllegalArgumentException("Round time limit must be 5, 10, or 15 minutes");
        }
        if (gameMode == null) {
            return;
        }
    }

    public Optional<Room> findByRoomCode(String roomCode) {
        return roomRepository.findByRoomCode(roomCode);
    }

    public List<Room> getWaitingRooms() {
        return roomRepository.findByStatus(RoomStatus.WAITING);
    }

    public List<Map<String, Object>> getWaitingRoomStates() {
        return roomRepository.findByStatus(RoomStatus.WAITING).stream()
                .sorted(Comparator.comparing(Room::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::getRoomState)
                .toList();
    }

    public List<Map<String, Object>> getAllRoomStates() {
        return roomRepository.findAll().stream()
                .sorted(Comparator.comparing(Room::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::getRoomState)
                .toList();
    }

    public long getPlayerCount(Room room) {
        Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
        return gameOpt.map(game -> (long) gamePlayerRepository.findByGameOrderBySeatIndexAsc(game).size()).orElse(0L);
    }

    public void joinRoom(String roomCode, User user) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("房间不存在"));

        if (room.getStatus() != RoomStatus.WAITING) {
            throw new IllegalArgumentException("房间已开始或已关闭");
        }

        long playerCount = getPlayerCount(room);
        if (playerCount >= room.getMaxPlayers()) {
            throw new IllegalArgumentException("房间已满");
        }
    }

    public void closeRoom(Room room) {
        room.setStatus(RoomStatus.CLOSED);
        roomRepository.save(room);
    }

    public void reopenRoom(Room room) {
        room.setStatus(RoomStatus.PLAYING);
        roomRepository.save(room);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRoomState(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("房间不存在: " + roomId));
        return getRoomState(room);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRoomState(Room room) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("roomId", room.getId());
        state.put("id", room.getId());
        state.put("roomCode", room.getRoomCode());
        state.put("status", room.getStatus().name());
        state.put("maxPlayers", room.getMaxPlayers());
        state.put("totalRounds", room.getTotalRounds());
        state.put("roundTimeLimitMinutes", room.getRoundTimeLimitMinutes());
        state.put("gameMode", room.getGameMode() == null ? GameMode.CLASSIC.name() : room.getGameMode().name());
        state.put("createdAt", room.getCreatedAt());

        Map<String, Object> host = new LinkedHashMap<>();
        host.put("userId", room.getHost().getId());
        host.put("username", room.getHost().getUsername());
        state.put("host", host);

        Optional<Game> gameOpt = gameRepository.findByRoom(room).stream().findFirst();
        List<Map<String, Object>> players = new ArrayList<>();
        Long gameId = null;
        String gameStatus = null;

        if (gameOpt.isPresent()) {
            Game game = gameOpt.get();
            gameId = game.getId();
            gameStatus = game.getStatus().name();

            List<GamePlayer> gamePlayers = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
            for (GamePlayer gp : gamePlayers) {
                Map<String, Object> player = new LinkedHashMap<>();
                player.put("userId", gp.getUser().getId());
                player.put("username", gp.getUser().getUsername());
                player.put("seatIndex", gp.getSeatIndex());
                player.put("handCount", gp.getHandCards().size());
                player.put("saidUno", gp.isSaidUno());
                player.put("host", gp.getUser().getId().equals(room.getHost().getId()));
                players.add(player);
            }
        }

        state.put("playerCount", players.size());
        state.put("players", players);
        state.put("playerNames", players.stream().map(player -> String.valueOf(player.get("username"))).toList());
        state.put("gameId", gameId);
        state.put("gameStatus", gameStatus);
        state.put("started", room.getStatus() == RoomStatus.PLAYING);
        return state;
    }
}
