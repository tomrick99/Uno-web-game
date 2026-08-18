package com.uno.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameServiceTurnLoopConsistencyTest {

    @Test
    void penaltyPatchCarriesCompletePendingStateForNextPlayer() {
        Fixture fixture = fixture(GameMode.CLASSIC, List.of(
                new Card(CardColor.RED, CardType.DRAW_TWO, 20),
                new Card(CardColor.BLUE, CardType.NUMBER, 1)
        ), List.of(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50)));

        fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 0, null);

        PublicGamePatch patch = fixture.template.lastPublicPatch;
        assertEquals("CARD_PLAYED", patch.type());
        assertEquals(2, patch.pendingPenalty());
        assertEquals(PendingDrawType.DRAW_TWO_CHAIN.name(), patch.pendingDrawType());
        assertEquals(fixture.alice.getId(), patch.lastPenaltyPlayerId());
        assertEquals(fixture.bob.getId(), patch.currentPlayerId());
        assertEquals(0, patch.drawPileSize());
    }

    @Test
    void noMercyPenaltyPatchCarriesDrawStackType() {
        Fixture fixture = fixture(GameMode.NO_MERCY, List.of(
                new Card(CardColor.RED, CardType.DRAW_FOUR, 40),
                new Card(CardColor.BLUE, CardType.NUMBER, 1)
        ), List.of(new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60)));

        fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 0, null);

        PublicGamePatch patch = fixture.template.lastPublicPatch;
        assertEquals(4, patch.pendingPenalty());
        assertEquals(PendingDrawType.DRAW_STACK.name(), patch.pendingDrawType());
        assertEquals(fixture.bob.getId(), patch.currentPlayerId());
    }

    @Test
    void discardAllKeepsDropCardOnTopAndDoesNotTriggerDiscardedPenalty() {
        Fixture fixture = fixture(GameMode.NO_MERCY, List.of(
                new Card(CardColor.RED, CardType.DRAW_TWO, 20),
                new Card(CardColor.RED, CardType.DISCARD_ALL_COLOR, 20),
                new Card(CardColor.RED, CardType.NUMBER, 5),
                new Card(CardColor.BLUE, CardType.NUMBER, 1)
        ), List.of(new Card(CardColor.GREEN, CardType.NUMBER, 7)));

        fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 1, null);

        PublicGamePatch patch = fixture.template.lastPublicPatch;
        assertEquals(CardType.DISCARD_ALL_COLOR.name(), patch.topCard().get("type"));
        assertEquals(0, patch.pendingPenalty());
        assertEquals(PendingDrawType.NONE.name(), patch.pendingDrawType());
        assertEquals(1, fixture.alicePlayer.getHandCards().size());
        assertEquals(CardColor.BLUE, fixture.alicePlayer.getHandCards().get(0).color());
    }

    @Test
    void finalPenaltyEndsImmediatelyWhenNextPlayerCouldStack() {
        assertFinalPenaltyEndsImmediately(List.of(
                new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50)
        ));
    }

    @Test
    void finalPenaltyEndsImmediatelyWhenNextPlayerCannotStack() {
        assertFinalPenaltyEndsImmediately(List.of(
                new Card(CardColor.GREEN, CardType.NUMBER, 7)
        ));
    }

    @Test
    void rematchReadyPatchCarriesWinnerAndReadiness() {
        Fixture fixture = fixture(GameMode.CLASSIC, List.of(), List.of(
                new Card(CardColor.GREEN, CardType.NUMBER, 7)
        ));
        fixture.game.setStatus(GameStatus.FINISHED);
        fixture.game.setCurrentTurn(null);
        fixture.room.setStatus(RoomStatus.CLOSED);

        fixture.service.readyForRematch(fixture.game.getId(), fixture.alice.getId());

        PublicGamePatch patch = fixture.template.lastPublicPatch;
        assertEquals("REMATCH_READY", patch.type());
        assertEquals(fixture.alice.getId(), patch.winnerId());
        assertEquals(List.of(fixture.alice.getId()), patch.rematchReadyPlayerIds());
        assertTrue(patch.players().get(0).rematchReady());
    }

    private void assertFinalPenaltyEndsImmediately(List<Card> bobHand) {
        Fixture fixture = fixture(GameMode.CLASSIC, List.of(
                new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50)
        ), bobHand);
        int bobHandSize = fixture.bobPlayer.getHandCards().size();

        fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 0, CardColor.BLUE);

        assertEquals(GameStatus.FINISHED, fixture.game.getStatus());
        assertNull(fixture.game.getCurrentTurn());
        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, fixture.game.getPendingDrawType());
        assertEquals(bobHandSize, fixture.bobPlayer.getHandCards().size());
        assertEquals(fixture.alice.getId(), fixture.template.lastPublicPatch.winnerId());
        assertEquals(RoomStatus.CLOSED.name(), fixture.template.lastPublicPatch.roomStatus());
    }

    private Fixture fixture(GameMode mode, List<Card> aliceHand, List<Card> bobHand) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, playerRepository);
        GameService service = new GameService(gameRepository, playerRepository, roomRepository, userRepository, wsService, roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("ROOM10");
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(2);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(mode);
        room.setCreatedAt(LocalDateTime.now());

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(alice.getId());
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawCount(0);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson("[]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");
        game.setCreatedAt(LocalDateTime.now());

        GamePlayer alicePlayer = player(game, alice, 0, aliceHand);
        GamePlayer bobPlayer = player(game, bob, 1, bobHand);
        List<GamePlayer> players = List.of(alicePlayer, bobPlayer);

        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(userRepository.getReferenceById(alice.getId())).thenReturn(alice);
        when(playerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        return new Fixture(service, template, room, game, alice, bob, alicePlayer, bobPlayer);
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private GamePlayer player(Game game, User user, int seatIndex, List<Card> hand) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>(hand));
        return player;
    }

    private record Fixture(GameService service,
                           RecordingTemplate template,
                           Room room,
                           Game game,
                           User alice,
                           User bob,
                           GamePlayer alicePlayer,
                           GamePlayer bobPlayer) {
    }

    private static final class RecordingTemplate extends SimpMessagingTemplate {
        private PublicGamePatch lastPublicPatch;

        private RecordingTemplate() {
            super(new NoOpMessageChannel());
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            if (payload instanceof PublicGamePatch patch) {
                lastPublicPatch = patch;
            }
        }

        @Override
        public void convertAndSendToUser(String user, String destination, Object payload) {
        }
    }

    private static final class NoOpMessageChannel implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return true;
        }
    }
}
