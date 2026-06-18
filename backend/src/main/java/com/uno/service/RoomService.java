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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

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

    public Map<String, Object> updateRoomConfigByAdmin(Long roomId,
                                                       int maxPlayers,
                                                       int totalRounds,
                                                       int roundTimeLimitMinutes,
                                                       GameMode gameMode) {
        validateRoomConfig(maxPlayers, totalRounds, roundTimeLimitMinutes, gameMode);
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room does not exist: " + roomId));
        if (room.getStatus() != RoomStatus.WAITING) {
            throw new IllegalArgumentException("Only waiting rooms can be edited");
        }

        long playerCount = getPlayerCount(room);
        if (playerCount > maxPlayers) {
            throw new IllegalArgumentException("New max players cannot be lower than current players");
        }

        room.setMaxPlayers(maxPlayers);
        room.setTotalRounds(totalRounds);
        room.setRoundTimeLimitMinutes(roundTimeLimitMinutes);
        room.setGameMode(gameMode == null ? GameMode.CLASSIC : gameMode);
        return getRoomState(roomRepository.save(room));
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
        long startedAt = System.nanoTime();
        try {
            return roomRepository.findByStatus(RoomStatus.WAITING).stream()
                    .sorted(Comparator.comparing(Room::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::getRoomState)
                    .toList();
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=buildWaitingRoomStates costMs={}", costMs);
        }
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
                .orElseThrow(() -> new IllegalArgumentException("Room does not exist"));

        if (room.getStatus() != RoomStatus.WAITING) {
            throw new IllegalArgumentException("Room is not joinable");
        }

        long playerCount = getPlayerCount(room);
        if (playerCount >= room.getMaxPlayers()) {
            throw new IllegalArgumentException("Room is full");
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
        long startedAt = System.nanoTime();
        try {
            Room room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room does not exist: " + roomId));
            return getRoomState(room);
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=getRoomState roomId={} costMs={}", roomId, costMs);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRoomState(Room room) {
        long startedAt = System.nanoTime();
        try {
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
            state.put("version", room.getCreatedAt() != null
                    ? room.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : System.currentTimeMillis());

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
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=buildRoomState roomId={} costMs={}", room.getId(), costMs);
        }
    }
}
