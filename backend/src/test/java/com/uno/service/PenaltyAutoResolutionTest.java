package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
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
import java.util.Optional;

import static com.uno.entity.enums.CardColor.RED;
import static com.uno.entity.enums.CardColor.WILD;
import static com.uno.entity.enums.CardType.NUMBER;
import static com.uno.entity.enums.CardType.WILD_DRAW_FOUR;
import static com.uno.entity.enums.CardType.WILD_DRAW_SIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PenaltyAutoResolutionTest {

    @Test
    void unstackablePlayerImmediatelyDrawsAndTurnAdvances() {
        Fixture fixture = fixture(List.of(new Card(RED, NUMBER, 7)));

        GamePlayer penalized = fixture.service.resolveUnstackablePenalty(fixture.game, fixture.players);

        assertSame(fixture.bobPlayer, penalized);
        assertEquals(5, fixture.bobPlayer.getHandCards().size());
        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, fixture.game.getPendingDrawType());
        assertEquals(3L, fixture.game.getCurrentTurn());
    }

    @Test
    void stackablePlayerKeepsAcceptOrStackDecision() {
        Fixture fixture = fixture(List.of(new Card(WILD, WILD_DRAW_SIX, 60)));

        GamePlayer penalized = fixture.service.resolveUnstackablePenalty(fixture.game, fixture.players);

        assertNull(penalized);
        assertEquals(1, fixture.bobPlayer.getHandCards().size());
        assertEquals(4, fixture.game.getPendingDrawCount());
        assertEquals(2L, fixture.game.getCurrentTurn());
    }

    @Test
    void acceptingPenaltyDrawsAndContinuesToNextPlayer() {
        Fixture fixture = fixture(List.of(new Card(WILD, WILD_DRAW_SIX, 60)));

        fixture.service.drawPenalty(20L, 2L);

        assertEquals(5, fixture.bobPlayer.getHandCards().size());
        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(3L, fixture.game.getCurrentTurn());
    }

    @Test
    void automaticPenaltyFollowsCounterClockwiseDirection() {
        Fixture fixture = fixture(List.of(new Card(RED, NUMBER, 7)));
        fixture.game.setClockwise(false);

        fixture.service.resolveUnstackablePenalty(fixture.game, fixture.players);

        assertEquals(1L, fixture.game.getCurrentTurn());
    }

    private Fixture fixture(List<Card> bobHand) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        GameWebSocketService wsService = new GameWebSocketService(new SimpMessagingTemplate(new NoOpMessageChannel()));
        RoomService roomService = new RoomService(roomRepository, gameRepository, playerRepository);
        GameService service = new GameService(gameRepository, playerRepository, roomRepository, userRepository, wsService, roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User carol = user(3L, "carol");
        Room room = new Room();
        room.setId(10L);
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setGameMode(GameMode.NO_MERCY);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(2L);
        game.setClockwise(true);
        game.setPendingDrawCount(4);
        game.setPendingDrawType(PendingDrawType.DRAW_STACK);
        game.setLastPenaltyPlayerId(1L);
        game.setCreatedAt(LocalDateTime.now());
        game.setDrawPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":1},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":2},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":3},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":4}]");
        game.setDiscardPileJson("[{\"color\":\"WILD\",\"type\":\"WILD_DRAW_FOUR\",\"value\":50}]");

        GamePlayer alicePlayer = player(game, alice, 0, List.of(new Card(WILD, WILD_DRAW_FOUR, 50)));
        GamePlayer bobPlayer = player(game, bob, 1, bobHand);
        GamePlayer carolPlayer = player(game, carol, 2, List.of(new Card(RED, NUMBER, 8)));
        List<GamePlayer> players = List.of(alicePlayer, bobPlayer, carolPlayer);

        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(bobPlayer));
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(2L)).thenReturn(bob);
        return new Fixture(service, game, players, bobPlayer);
    }

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private GamePlayer player(Game game, User user, int seat, List<Card> hand) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seat);
        player.setHandCards(new ArrayList<>(hand));
        return player;
    }

    private record Fixture(GameService service, Game game, List<GamePlayer> players, GamePlayer bobPlayer) {}

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
