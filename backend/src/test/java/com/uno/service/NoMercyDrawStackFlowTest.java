package com.uno.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoMercyDrawStackFlowTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void equalAndHigherPenaltiesStackAndReverseFourRedirectsTheFullTotal() throws Exception {
        Fixture fixture = fixture();

        fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 0, null);

        assertEquals(2, fixture.game.getPendingDrawCount());
        assertEquals(fixture.bob.getId(), fixture.game.getCurrentTurn());
        assertEquals(3, fixture.bobPlayer.getHandCards().size());

        fixture.service.playCard(fixture.game.getId(), fixture.bob.getId(), 1, null);

        assertEquals(4, fixture.game.getPendingDrawCount());
        assertEquals(fixture.carol.getId(), fixture.game.getCurrentTurn());

        fixture.service.playCard(fixture.game.getId(), fixture.carol.getId(), 0, CardColor.GREEN);

        assertEquals(8, fixture.game.getPendingDrawCount());
        assertEquals(fixture.bob.getId(), fixture.game.getCurrentTurn());
        assertEquals(false, fixture.game.isClockwise());

        fixture.service.playCard(fixture.game.getId(), fixture.bob.getId(), 0, CardColor.YELLOW);

        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, fixture.game.getPendingDrawType());
        assertEquals(15, fixture.alicePlayer.getHandCards().size());
        assertEquals(fixture.carol.getId(), fixture.game.getCurrentTurn());
    }

    private Fixture fixture() throws Exception {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoomService roomService = mock(RoomService.class);
        GameWebSocketService wsService = new GameWebSocketService(
                new SimpMessagingTemplate(new NoOpMessageChannel()));
        GameService service = new GameService(
                gameRepository, playerRepository, roomRepository, userRepository, wsService, roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User carol = user(3L, "carol");

        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("STACK");
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(3);
        room.setGameMode(GameMode.NO_MERCY);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(alice.getId());
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setPendingDrawCount(0);
        game.setCreatedAt(LocalDateTime.now());
        game.setDiscardPileJson(cardsJson(List.of(new Card(CardColor.RED, CardType.NUMBER, 9))));

        List<Card> drawPile = new ArrayList<>();
        for (int value = 0; value < 14; value++) {
            drawPile.add(new Card(CardColor.GREEN, CardType.NUMBER, value % 10));
        }
        game.setDrawPileJson(cardsJson(drawPile));

        GamePlayer alicePlayer = player(game, alice, 0, List.of(
                new Card(CardColor.RED, CardType.DRAW_TWO, 20),
                new Card(CardColor.RED, CardType.NUMBER, 7)));
        GamePlayer bobPlayer = player(game, bob, 1, List.of(
                new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60),
                new Card(CardColor.BLUE, CardType.DRAW_TWO, 20),
                new Card(CardColor.BLUE, CardType.NUMBER, 8)));
        GamePlayer carolPlayer = player(game, carol, 2, List.of(
                new Card(CardColor.WILD, CardType.WILD_REVERSE_DRAW_FOUR, 50),
                new Card(CardColor.YELLOW, CardType.NUMBER, 6)));
        List<GamePlayer> players = List.of(alicePlayer, bobPlayer, carolPlayer);

        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(playerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(bobPlayer));
        when(playerRepository.findByGameAndUser(game, carol)).thenReturn(Optional.of(carolPlayer));
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(alice.getId())).thenReturn(alice);
        when(userRepository.getReferenceById(bob.getId())).thenReturn(bob);
        when(userRepository.getReferenceById(carol.getId())).thenReturn(carol);

        return new Fixture(service, game, alice, bob, carol, alicePlayer, bobPlayer);
    }

    private String cardsJson(List<Card> cards) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(cards);
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
        player.setHandCards(hand);
        return player;
    }

    private record Fixture(GameService service,
                           Game game,
                           User alice,
                           User bob,
                           User carol,
                           GamePlayer alicePlayer,
                           GamePlayer bobPlayer) {
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
