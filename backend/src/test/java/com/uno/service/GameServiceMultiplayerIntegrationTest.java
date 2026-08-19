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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uno_multiplayer;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class GameServiceMultiplayerIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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
            PublicGamePatch patch = invocation.getArgument(0);
            assertPatchMatchesCommittedDatabaseState(patch);
            publicPatches.add(patch);
            return null;
        }).when(wsService).broadcastPublicGamePatch(any(PublicGamePatch.class));

        doAnswer(invocation -> {
            privateHandPatches.add(invocation.getArgument(4));
            return null;
        }).when(wsService).sendPrivateHandPatch(any(), any(), any(), any(), any(PrivateHandPatch.class));
    }

    @Test
    void threePlayerNormalTurnsCycleAliceToBobToCarolToAlice() {
        Fixture fixture = createGame(List.of(
                List.of(number(CardColor.RED, 1), number(CardColor.BLUE, 1)),
                List.of(number(CardColor.RED, 2), number(CardColor.BLUE, 2)),
                List.of(number(CardColor.RED, 3), number(CardColor.BLUE, 3))
        ), drawPile(12));

        long version = 0;
        gameService.playCard(fixture.gameId(), fixture.userId(0), 0, null);
        version = assertPatch(lastPublicPatch(), fixture, 1, 1, 1, 0,
                PendingDrawType.NONE, List.of(1, 2, 2), version);

        gameService.playCard(fixture.gameId(), fixture.userId(1), 0, null);
        version = assertPatch(lastPublicPatch(), fixture, 2, 2, 1, 0,
                PendingDrawType.NONE, List.of(1, 1, 2), version);

        gameService.playCard(fixture.gameId(), fixture.userId(2), 0, null);
        assertPatch(lastPublicPatch(), fixture, 0, 0, 1, 0,
                PendingDrawType.NONE, List.of(1, 1, 1), version);
    }

    @Test
    void threePlayerSkipMovesAliceDirectlyToCarol() {
        Fixture fixture = createGame(List.of(
                List.of(action(CardColor.RED, CardType.SKIP), number(CardColor.BLUE, 1)),
                List.of(number(CardColor.YELLOW, 2), number(CardColor.BLUE, 2)),
                List.of(number(CardColor.GREEN, 3), number(CardColor.BLUE, 3))
        ), drawPile(12));

        gameService.playCard(fixture.gameId(), fixture.userId(0), 0, null);

        assertPatch(lastPublicPatch(), fixture, 2, 2, 1, 0,
                PendingDrawType.NONE, List.of(1, 2, 2), 0);
    }

    @Test
    void threePlayerReverseMovesAliceToCarolThenCarolToBob() {
        Fixture fixture = createGame(List.of(
                List.of(action(CardColor.RED, CardType.REVERSE), number(CardColor.BLUE, 1)),
                List.of(number(CardColor.YELLOW, 2), number(CardColor.BLUE, 2)),
                List.of(number(CardColor.RED, 3), number(CardColor.BLUE, 3))
        ), drawPile(12));

        gameService.playCard(fixture.gameId(), fixture.userId(0), 0, null);
        long version = assertPatch(lastPublicPatch(), fixture, 2, 2, -1, 0,
                PendingDrawType.NONE, List.of(1, 2, 2), 0);

        gameService.playCard(fixture.gameId(), fixture.userId(2), 0, null);
        assertPatch(lastPublicPatch(), fixture, 1, 1, -1, 0,
                PendingDrawType.NONE, List.of(1, 2, 1), version);
    }

    @Test
    void threePlayerPenaltyChainStacksTwoAndFourThenAutoDrawsSix() {
        Fixture fixture = createGame(List.of(
                List.of(action(CardColor.RED, CardType.DRAW_TWO), number(CardColor.BLUE, 1)),
                List.of(action(CardColor.WILD, CardType.WILD), action(CardColor.WILD, CardType.WILD_DRAW_FOUR)),
                List.of(number(CardColor.GREEN, 3), number(CardColor.BLUE, 3))
        ), drawPile(12));

        gameService.playCard(fixture.gameId(), fixture.userId(0), 0, null);
        long version = assertPatch(lastPublicPatch(), fixture, 1, 1, 1, 2,
                PendingDrawType.DRAW_TWO_CHAIN, List.of(1, 2, 2), 0);
        assertEquals(fixture.userId(0), lastPublicPatch().lastPenaltyPlayerId());

        int privatePatchCountBeforeStack = privateHandPatches.size();
        gameService.playCard(fixture.gameId(), fixture.userId(1), 1, CardColor.RED);

        PublicGamePatch patch = lastPublicPatch();
        assertPatch(patch, fixture, 0, 0, 1, 0,
                PendingDrawType.NONE, List.of(1, 1, 8), version);
        assertNull(patch.lastPenaltyPlayerId());
        assertEquals(privatePatchCountBeforeStack + 2, privateHandPatches.size());
        PrivateHandPatch carolPatch = privateHandPatches.get(privateHandPatches.size() - 1);
        assertEquals(fixture.userId(2), carolPatch.userId());
        assertEquals(8, carolPatch.handCards().size());
        assertEquals(0, carolPatch.pendingPenalty());
    }

    @Test
    void fourPlayerNormalTurnsCycleAcrossEverySeat() {
        Fixture fixture = createGame(List.of(
                List.of(number(CardColor.RED, 1), number(CardColor.BLUE, 1)),
                List.of(number(CardColor.RED, 2), number(CardColor.BLUE, 2)),
                List.of(number(CardColor.RED, 3), number(CardColor.BLUE, 3)),
                List.of(number(CardColor.RED, 4), number(CardColor.BLUE, 4))
        ), drawPile(16));

        long version = 0;
        for (int actorIndex = 0; actorIndex < 4; actorIndex++) {
            gameService.playCard(fixture.gameId(), fixture.userId(actorIndex), 0, null);
            int nextIndex = (actorIndex + 1) % 4;
            List<Integer> expectedHandCounts = new ArrayList<>(List.of(2, 2, 2, 2));
            for (int playedIndex = 0; playedIndex <= actorIndex; playedIndex++) {
                expectedHandCounts.set(playedIndex, 1);
            }
            version = assertPatch(lastPublicPatch(), fixture, nextIndex, nextIndex, 1, 0,
                    PendingDrawType.NONE, expectedHandCounts, version);
        }
    }

    @Test
    void actionNotificationsWaitForCommitAndAreDiscardedOnRollback() {
        Fixture committedFixture = createGame(List.of(
                List.of(number(CardColor.RED, 1), number(CardColor.BLUE, 1)),
                List.of(number(CardColor.RED, 2), number(CardColor.BLUE, 2)),
                List.of(number(CardColor.RED, 3), number(CardColor.BLUE, 3))
        ), drawPile(12));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            gameService.playCard(committedFixture.gameId(), committedFixture.userId(0), 0, null);
            assertTrue(publicPatches.isEmpty());
            assertTrue(privateHandPatches.isEmpty());
        });

        assertEquals(1, publicPatches.size());
        assertEquals(1, privateHandPatches.size());

        Fixture rolledBackFixture = createGame(List.of(
                List.of(number(CardColor.RED, 4), number(CardColor.BLUE, 4)),
                List.of(number(CardColor.RED, 5), number(CardColor.BLUE, 5)),
                List.of(number(CardColor.RED, 6), number(CardColor.BLUE, 6))
        ), drawPile(12));
        publicPatches.clear();
        privateHandPatches.clear();
        clearInvocations(wsService);

        transaction.executeWithoutResult(status -> {
            gameService.playCard(rolledBackFixture.gameId(), rolledBackFixture.userId(0), 0, null);
            assertTrue(publicPatches.isEmpty());
            assertTrue(privateHandPatches.isEmpty());
            status.setRollbackOnly();
        });

        assertTrue(publicPatches.isEmpty());
        assertTrue(privateHandPatches.isEmpty());
        Game rolledBackGame = gameRepository.findById(rolledBackFixture.gameId()).orElseThrow();
        assertEquals(rolledBackFixture.userId(0), rolledBackGame.getCurrentTurn());
        assertEquals(2, committedPlayers(rolledBackFixture.gameId()).get(0).getHandCards().size());
    }

    @Test
    void drawAndPenaltyNotificationsAlsoWaitForCommit() {
        Fixture drawFixture = createGame(List.of(
                List.of(number(CardColor.BLUE, 1)),
                List.of(number(CardColor.GREEN, 2)),
                List.of(number(CardColor.YELLOW, 3))
        ), drawPile(12));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            gameService.drawCard(drawFixture.gameId(), drawFixture.userId(0));
            assertTrue(publicPatches.isEmpty());
            assertTrue(privateHandPatches.isEmpty());
        });
        assertEquals("CARD_DRAWN_PUBLIC", lastPublicPatch().type());
        assertEquals(drawFixture.userId(1), lastPublicPatch().currentPlayerId());
        assertEquals(2, lastPublicPatch().players().get(0).handCount());

        Fixture penaltyFixture = createGame(List.of(
                List.of(number(CardColor.BLUE, 4)),
                List.of(number(CardColor.GREEN, 5)),
                List.of(number(CardColor.YELLOW, 6))
        ), drawPile(12));
        TransactionTemplate requiresNew = requiresNewTransaction();
        requiresNew.executeWithoutResult(status -> {
            Game game = gameRepository.findById(penaltyFixture.gameId()).orElseThrow();
            game.setPendingDrawCount(2);
            game.setPendingDrawType(PendingDrawType.DRAW_TWO_CHAIN);
            game.setLastPenaltyPlayerId(penaltyFixture.userId(2));
            game.setDiscardPileJson(toJson(List.of(action(CardColor.RED, CardType.DRAW_TWO))));
            gameRepository.save(game);
        });
        publicPatches.clear();
        privateHandPatches.clear();
        clearInvocations(wsService);

        transaction.executeWithoutResult(status -> {
            gameService.drawPenalty(penaltyFixture.gameId(), penaltyFixture.userId(0));
            assertTrue(publicPatches.isEmpty());
            assertTrue(privateHandPatches.isEmpty());
        });

        PublicGamePatch penaltyPatch = lastPublicPatch();
        assertEquals("PENALTY_UPDATED", penaltyPatch.type());
        assertEquals(penaltyFixture.userId(1), penaltyPatch.currentPlayerId());
        assertEquals(0, penaltyPatch.pendingPenalty());
        assertEquals(PendingDrawType.NONE.name(), penaltyPatch.pendingDrawType());
        assertEquals(3, penaltyPatch.players().get(0).handCount());
    }

    private long assertPatch(PublicGamePatch patch,
                             Fixture fixture,
                             int expectedCurrentPlayer,
                             int expectedCurrentPlayerIndex,
                             int expectedDirection,
                             int expectedPendingPenalty,
                             PendingDrawType expectedPendingDrawType,
                             List<Integer> expectedHandCounts,
                             long previousVersion) {
        assertEquals("CARD_PLAYED", patch.type());
        assertEquals(fixture.userId(expectedCurrentPlayer), patch.currentPlayerId());
        assertEquals(expectedCurrentPlayerIndex, patch.currentPlayerIndex());
        assertEquals(expectedDirection, patch.direction());
        assertEquals(expectedPendingPenalty, patch.pendingPenalty());
        assertEquals(expectedPendingDrawType.name(), patch.pendingDrawType());
        assertNotNull(patch.version());
        assertTrue(patch.version() > previousVersion);
        assertEquals(expectedHandCounts, patch.players().stream().map(player -> player.handCount()).toList());
        return patch.version();
    }

    private void assertPatchMatchesCommittedDatabaseState(PublicGamePatch patch) {
        requiresNewTransaction().executeWithoutResult(status -> {
            Game game = gameRepository.findById(patch.gameId()).orElseThrow();
            List<GamePlayer> players = gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
            assertEquals(game.getCurrentTurn(), patch.currentPlayerId());
            assertEquals(game.isClockwise() ? 1 : -1, patch.direction());
            assertEquals(game.getPendingDrawCount(), patch.pendingPenalty());
            assertEquals(game.getPendingDrawType().name(), patch.pendingDrawType());
            assertEquals(players.stream().map(player -> player.getHandCards().size()).toList(),
                    patch.players().stream().map(player -> player.handCount()).toList());
        });
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private PublicGamePatch lastPublicPatch() {
        assertFalse(publicPatches.isEmpty());
        return publicPatches.get(publicPatches.size() - 1);
    }

    private List<GamePlayer> committedPlayers(Long gameId) {
        Game game = gameRepository.findById(gameId).orElseThrow();
        return gamePlayerRepository.findByGameOrderBySeatIndexAsc(game);
    }

    private Fixture createGame(List<List<Card>> hands, List<Card> drawPile) {
        List<User> users = new ArrayList<>();
        int sequence = ROOM_SEQUENCE.incrementAndGet();
        for (int index = 0; index < hands.size(); index++) {
            User user = new User();
            user.setUsername("p" + sequence + "_" + index);
            user.setPassword("test-password");
            users.add(userRepository.save(user));
        }

        Room room = new Room();
        room.setRoomCode("T" + sequence);
        room.setHost(users.get(0));
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(hands.size());
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        room = roomRepository.save(room);

        Game game = new Game();
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(users.get(0).getId());
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawCount(0);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson(toJson(drawPile));
        game.setDiscardPileJson(toJson(List.of(number(CardColor.RED, 9))));
        game = gameRepository.save(game);

        for (int index = 0; index < hands.size(); index++) {
            GamePlayer player = new GamePlayer();
            player.setGame(game);
            player.setUser(users.get(index));
            player.setSeatIndex(index);
            player.setHandCards(hands.get(index));
            gamePlayerRepository.save(player);
        }

        publicPatches.clear();
        privateHandPatches.clear();
        clearInvocations(wsService);
        return new Fixture(game.getId(), users.stream().map(User::getId).toList());
    }

    private String toJson(List<Card> cards) {
        try {
            return OBJECT_MAPPER.writeValueAsString(cards);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<Card> drawPile(int count) {
        List<Card> cards = new ArrayList<>();
        CardColor[] colors = {CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE};
        for (int index = 0; index < count; index++) {
            cards.add(number(colors[index % colors.length], index % 10));
        }
        return cards;
    }

    private Card number(CardColor color, int value) {
        return new Card(color, CardType.NUMBER, value);
    }

    private Card action(CardColor color, CardType type) {
        return new Card(color, type, 20);
    }

    private record Fixture(Long gameId, List<Long> userIds) {
        private Long userId(int index) {
            return userIds.get(index);
        }
    }
}
