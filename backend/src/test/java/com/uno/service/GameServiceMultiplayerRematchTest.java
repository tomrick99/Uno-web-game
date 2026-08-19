package com.uno.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.PendingDrawType;
import com.uno.entity.enums.RoomStatus;
import com.uno.model.Card;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uno_multiplayer_rematch;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class GameServiceMultiplayerRematchTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicInteger ROOM_SEQUENCE = new AtomicInteger();

    @Autowired
    private GameService gameService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GamePlayerRepository gamePlayerRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private GameWebSocketService wsService;

    private List<PublicGamePatch> publicPatches;
    private List<PrivateHandPatch> privateHandPatches;

    @BeforeEach
    void setUp() {
        gamePlayerRepository.deleteAll();
        gameRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();

        reset(wsService);
        publicPatches = new CopyOnWriteArrayList<>();
        privateHandPatches = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            publicPatches.add(invocation.getArgument(0));
            return null;
        }).when(wsService).broadcastPublicGamePatch(any(PublicGamePatch.class));

        doAnswer(invocation -> {
            privateHandPatches.add(invocation.getArgument(4));
            return null;
        }).when(wsService).sendPrivateHandPatch(any(), any(), any(), any(), any(PrivateHandPatch.class));
    }

    @Test
    void threePlayersOneReadyKeepsGameFinished() {
        Fixture fixture = createFinishedGame(3);

        Map<String, Object> ack = ready(fixture, 0);

        assertEquals("REMATCH_READY", ack.get("type"));
        assertFinishedWithReadyPlayers(fixture, List.of(fixture.userId(0)));
        assertEquals(List.of(fixture.userId(0)), lastPublicPatch().rematchReadyPlayerIds());
        assertNoRestartPublished();
    }

    @Test
    void threePlayersTwoReadyStillWaitsForThirdPlayer() {
        Fixture fixture = createFinishedGame(3);

        ready(fixture, 0);
        Map<String, Object> ack = ready(fixture, 1);

        assertEquals("REMATCH_READY", ack.get("type"));
        assertFinishedWithReadyPlayers(fixture, List.of(fixture.userId(0), fixture.userId(1)));
        assertEquals(List.of(fixture.userId(0), fixture.userId(1)), lastPublicPatch().rematchReadyPlayerIds());
        assertNoRestartPublished();
    }

    @Test
    void threePlayersAllReadyStartsNewRoundAndClearsReadiness() {
        Fixture fixture = createFinishedGame(3);

        ready(fixture, 0);
        ready(fixture, 1);
        Map<String, Object> ack = ready(fixture, 2);

        assertEquals("GAME_RESTARTED", ack.get("type"));
        assertRestartedRound(fixture);
        assertEquals(1, patchesOfType("GAME_RESTARTED").size());
        assertEquals(3, privateHandPatches.size());
    }

    @Test
    void fourPlayersThreeReadyDoesNotRestart() {
        Fixture fixture = createFinishedGame(4);

        ready(fixture, 0);
        ready(fixture, 1);
        ready(fixture, 2);

        assertFinishedWithReadyPlayers(fixture,
                List.of(fixture.userId(0), fixture.userId(1), fixture.userId(2)));
        assertNoRestartPublished();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gameService.restartGame(fixture.gameId(), fixture.userId(0)));
        assertEquals("All players must be ready before restarting", error.getMessage());
    }

    @Test
    void fourPlayersAllReadyRestartsExactlyOnce() {
        Fixture fixture = createFinishedGame(4);

        ready(fixture, 0);
        ready(fixture, 1);
        ready(fixture, 2);
        Map<String, Object> ack = ready(fixture, 3);

        assertEquals("GAME_RESTARTED", ack.get("type"));
        assertRestartedRound(fixture);
        assertEquals(1, patchesOfType("GAME_RESTARTED").size());
        assertEquals(4, privateHandPatches.size());
    }

    @Test
    void duplicateReadyRequestIsIdempotent() {
        Fixture fixture = createFinishedGame(4);

        Map<String, Object> firstAck = ready(fixture, 0);
        Map<String, Object> duplicateAck = ready(fixture, 0);

        assertEquals(firstAck.get("version"), duplicateAck.get("version"));
        assertFinishedWithReadyPlayers(fixture, List.of(fixture.userId(0)));
        assertNoRestartPublished();
        assertEquals(2, publicPatches.size());
        for (PublicGamePatch patch : publicPatches) {
            assertEquals(List.of(fixture.userId(0)), patch.rematchReadyPlayerIds());
            assertEquals(firstAck.get("version"), patch.version());
        }
    }

    @Test
    void rematchReadyPatchCarriesWinnerReadinessStatusAndVersion() {
        Fixture fixture = createFinishedGame(3);

        Map<String, Object> ack = ready(fixture, 0);

        PublicGamePatch patch = lastPublicPatch();
        assertEquals("REMATCH_READY", patch.type());
        assertEquals(fixture.userId(0), patch.winnerId());
        assertEquals(List.of(fixture.userId(0)), patch.rematchReadyPlayerIds());
        assertEquals(List.of(true, false, false),
                patch.players().stream().map(player -> player.rematchReady()).toList());
        assertEquals(GameStatus.FINISHED.name(), patch.gameStatus());
        assertEquals(RoomStatus.CLOSED.name(), patch.roomStatus());
        assertNull(patch.currentPlayerId());
        assertNotNull(patch.version());
        assertEquals(ack.get("version"), patch.version());
    }

    @Test
    void gameRestartedPatchCarriesClearedReadinessAndPlayingState() {
        Fixture fixture = createFinishedGame(3);

        ready(fixture, 0);
        ready(fixture, 1);
        Map<String, Object> ack = ready(fixture, 2);

        PublicGamePatch patch = lastPublicPatch();
        assertEquals("GAME_RESTARTED", patch.type());
        assertEquals(GameStatus.PLAYING.name(), patch.gameStatus());
        assertEquals(RoomStatus.PLAYING.name(), patch.roomStatus());
        assertEquals(fixture.userId(0), patch.currentPlayerId());
        assertEquals(0, patch.currentPlayerIndex());
        assertNull(patch.winnerId());
        assertTrue(patch.rematchReadyPlayerIds().isEmpty());
        assertTrue(patch.players().stream().noneMatch(player -> player.rematchReady()));
        assertEquals(List.of(7, 7, 7), patch.players().stream().map(player -> player.handCount()).toList());
        assertEquals(ack.get("version"), patch.version());
    }

    @Test
    void twoPlayerRematchStillWaitsAtOneAndRestartsAtTwo() {
        Fixture fixture = createFinishedGame(2);

        Map<String, Object> firstAck = ready(fixture, 0);
        assertEquals("REMATCH_READY", firstAck.get("type"));
        assertFinishedWithReadyPlayers(fixture, List.of(fixture.userId(0)));
        assertNoRestartPublished();

        Map<String, Object> secondAck = ready(fixture, 1);
        assertEquals("GAME_RESTARTED", secondAck.get("type"));
        assertRestartedRound(fixture);
        assertEquals(1, patchesOfType("GAME_RESTARTED").size());
    }

    private Map<String, Object> ready(Fixture fixture, int playerIndex) {
        return gameService.readyForRematch(fixture.gameId(), fixture.userId(playerIndex));
    }

    private void assertFinishedWithReadyPlayers(Fixture fixture, List<Long> expectedReadyIds) {
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
        Room room = roomRepository.findById(fixture.roomId()).orElseThrow();
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals(RoomStatus.CLOSED, room.getStatus());
        assertEquals(expectedReadyIds,
                players.stream()
                        .filter(GamePlayer::isRematchReady)
                        .map(player -> player.getUser().getId())
                        .toList());
    }

    private void assertRestartedRound(Fixture fixture) {
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
        Room room = roomRepository.findById(fixture.roomId()).orElseThrow();
        List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);

        assertEquals(GameStatus.PLAYING, game.getStatus());
        assertEquals(RoomStatus.PLAYING, room.getStatus());
        assertEquals(fixture.userId(0), game.getCurrentTurn());
        assertTrue(game.isClockwise());
        assertEquals(0, game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, game.getPendingDrawType());
        assertNotNull(game.getCurrentColor());
        assertTrue(players.stream().noneMatch(GamePlayer::isRematchReady));
        assertTrue(players.stream().allMatch(player -> player.getHandCards().size() == 7));
    }

    private void assertNoRestartPublished() {
        assertTrue(patchesOfType("GAME_RESTARTED").isEmpty());
        assertTrue(privateHandPatches.isEmpty());
    }

    private List<PublicGamePatch> patchesOfType(String type) {
        return publicPatches.stream().filter(patch -> type.equals(patch.type())).toList();
    }

    private PublicGamePatch lastPublicPatch() {
        assertFalse(publicPatches.isEmpty());
        return publicPatches.get(publicPatches.size() - 1);
    }

    private Fixture createFinishedGame(int playerCount) {
        int sequence = ROOM_SEQUENCE.incrementAndGet();
        List<User> users = new ArrayList<>();
        for (int index = 0; index < playerCount; index++) {
            User user = new User();
            user.setUsername("rematch_" + sequence + "_" + index);
            user.setPassword("test-password");
            users.add(userRepository.save(user));
        }

        Room room = new Room();
        room.setRoomCode("RM" + sequence);
        room.setHost(users.get(0));
        room.setStatus(RoomStatus.CLOSED);
        room.setMaxPlayers(playerCount);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        room = roomRepository.save(room);

        Game game = new Game();
        game.setRoom(room);
        game.setStatus(GameStatus.FINISHED);
        game.setCurrentTurn(null);
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawCount(0);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson(toJson(List.of(number(CardColor.BLUE, 4))));
        game.setDiscardPileJson(toJson(List.of(number(CardColor.RED, 9))));
        game = gameRepository.save(game);

        for (int index = 0; index < playerCount; index++) {
            GamePlayer player = new GamePlayer();
            player.setGame(game);
            player.setUser(users.get(index));
            player.setSeatIndex(index);
            player.setHandCards(index == 0
                    ? List.of()
                    : List.of(number(CardColor.values()[index % 4], index)));
            player.setRematchReady(false);
            gamePlayerRepository.save(player);
        }

        publicPatches.clear();
        privateHandPatches.clear();
        clearInvocations(wsService);
        return new Fixture(room.getId(), game.getId(), users.stream().map(User::getId).toList());
    }

    private String toJson(List<Card> cards) {
        try {
            return OBJECT_MAPPER.writeValueAsString(cards);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Card number(CardColor color, int value) {
        return new Card(color, CardType.NUMBER, value);
    }

    private record Fixture(Long roomId, Long gameId, List<Long> userIds) {
        private Long userId(int index) {
            return userIds.get(index);
        }
    }
}
