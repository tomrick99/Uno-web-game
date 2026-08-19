package com.uno.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.uno.model.Card;
import com.uno.model.Deck;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceInitialDiscardTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GameRepository gameRepository;
    private GamePlayerRepository gamePlayerRepository;
    private GameService gameService;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoomService roomService = mock(RoomService.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );
    }

    @Test
    void firstColoredNumberIsUsedWithoutReplacement() {
        Card number = number(CardColor.GREEN, 7);
        Card remaining = number(CardColor.BLUE, 2);
        Deck deck = deckInDrawOrder(GameMode.CLASSIC, List.of(number, remaining));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        Card selected = gameService.drawInitialNumberCard(deck);

        assertSame(number, selected);
        assertSelectionConservesCards(before, selected, deck);
    }

    @Test
    void wildIsReturnedToDrawPileAndFollowingNumberBecomesDiscardCandidate() {
        Card wild = action(CardColor.WILD, CardType.WILD);
        Card number = number(CardColor.YELLOW, 4);
        Deck deck = deckInDrawOrder(GameMode.CLASSIC, List.of(wild, number));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        Card selected = gameService.drawInitialNumberCard(deck);

        assertSame(number, selected);
        assertContainsSameInstance(deck.getDrawPile(), wild);
        assertSelectionConservesCards(before, selected, deck);
    }

    @Test
    void wildDrawFourIsReturnedToDrawPileAndDoesNotDisappear() {
        Card wildDrawFour = action(CardColor.WILD, CardType.WILD_DRAW_FOUR);
        Card number = number(CardColor.RED, 6);
        Deck deck = deckInDrawOrder(GameMode.CLASSIC, List.of(wildDrawFour, number));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        Card selected = gameService.drawInitialNumberCard(deck);

        assertSame(number, selected);
        assertContainsSameInstance(deck.getDrawPile(), wildDrawFour);
        assertSelectionConservesCards(before, selected, deck);
    }

    @ParameterizedTest
    @EnumSource(value = CardType.class, names = {"SKIP", "REVERSE", "DRAW_TWO"})
    void classicActionCardsCannotBecomeInitialDiscard(CardType actionType) {
        Card action = action(CardColor.RED, actionType);
        Card number = number(CardColor.BLUE, 5);
        Deck deck = deckInDrawOrder(GameMode.CLASSIC, List.of(action, number));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        Card selected = gameService.drawInitialNumberCard(deck);

        assertSame(number, selected);
        assertContainsSameInstance(deck.getDrawPile(), action);
        assertSelectionConservesCards(before, selected, deck);
    }

    @ParameterizedTest
    @EnumSource(value = CardType.class, names = {"DISCARD_ALL_COLOR", "SKIP_ALL", "DRAW_FOUR"})
    void noMercyColoredActionCardsCannotBecomeInitialDiscard(CardType actionType) {
        Card action = action(CardColor.GREEN, actionType);
        Card number = number(CardColor.YELLOW, 8);
        Deck deck = deckInDrawOrder(GameMode.NO_MERCY, List.of(action, number));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        Card selected = gameService.drawInitialNumberCard(deck);

        assertSame(number, selected);
        assertContainsSameInstance(deck.getDrawPile(), action);
        assertSelectionConservesCards(before, selected, deck);
    }

    @Test
    void startGamePreservesEveryCardAndInitializesAColoredNumberDiscard() {
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Room room = new Room();
        room.setGameMode(GameMode.NO_MERCY);

        Game game = new Game();
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        game.setClockwise(false);
        game.setPendingDrawCount(12);
        game.setPendingDrawType(PendingDrawType.DRAW_STACK);
        game.setLastPenaltyPlayerId(bob.getId());

        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);
        List<GamePlayer> players = List.of(alicePlayer, bobPlayer);
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Card> dealOrder = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            CardColor color = List.of(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE).get(index % 4);
            dealOrder.add(number(color, index % 10));
        }
        List<Card> rejected = List.of(
                action(CardColor.WILD, CardType.WILD),
                action(CardColor.WILD, CardType.WILD_DRAW_FOUR),
                action(CardColor.RED, CardType.SKIP),
                action(CardColor.YELLOW, CardType.REVERSE),
                action(CardColor.BLUE, CardType.DRAW_TWO),
                action(CardColor.GREEN, CardType.DISCARD_ALL_COLOR),
                action(CardColor.RED, CardType.SKIP_ALL),
                action(CardColor.BLUE, CardType.DRAW_FOUR)
        );
        Card initialNumber = number(CardColor.YELLOW, 5);
        Card untouchedTail = action(CardColor.WILD, CardType.WILD_DRAW_SIX);
        dealOrder.addAll(rejected);
        dealOrder.add(initialNumber);
        dealOrder.add(untouchedTail);

        Deck deck = deckInDrawOrder(GameMode.NO_MERCY, dealOrder);
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        gameService.startGame(game, deck);

        List<Card> persistedDrawPile = readCards(game.getDrawPileJson());
        List<Card> persistedDiscardPile = readCards(game.getDiscardPileJson());
        List<Card> allCardsAfter = new ArrayList<>();
        allCardsAfter.addAll(alicePlayer.getHandCards());
        allCardsAfter.addAll(bobPlayer.getHandCards());
        allCardsAfter.addAll(persistedDrawPile);
        allCardsAfter.addAll(persistedDiscardPile);

        assertEquals(before, cardMultiset(allCardsAfter));
        assertEquals(7, alicePlayer.getHandCards().size());
        assertEquals(7, bobPlayer.getHandCards().size());
        assertEquals(before.values().stream().mapToLong(Long::longValue).sum(), allCardsAfter.size());
        assertEquals(1, persistedDiscardPile.size());
        Card persistedInitialDiscard = persistedDiscardPile.get(0);
        assertEquals(initialNumber.color(), persistedInitialDiscard.color());
        assertEquals(initialNumber.type(), persistedInitialDiscard.type());
        assertEquals(initialNumber.value(), persistedInitialDiscard.value());
        assertEquals(CardType.NUMBER, persistedInitialDiscard.type());
        assertFalse(persistedInitialDiscard.color() == CardColor.WILD);
        assertEquals(initialNumber.color(), game.getCurrentColor());
        assertEquals(alice.getId(), game.getCurrentTurn());
        assertTrue(game.isClockwise());
        assertEquals(0, game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, game.getPendingDrawType());
        assertEquals(GameStatus.PLAYING, game.getStatus());
        Map<String, Long> persistedDrawCounts = cardMultiset(persistedDrawPile);
        for (Card rejectedCard : rejected) {
            assertTrue(persistedDrawCounts.getOrDefault(cardKey(rejectedCard), 0L) > 0,
                    "Rejected card must remain in draw pile: " + cardKey(rejectedCard));
        }
        verify(gamePlayerRepository, times(2)).save(any(GamePlayer.class));
        verify(gameRepository).save(game);
    }

    @Test
    void missingColoredNumberRestoresRejectedCardsBeforeFailing() {
        Card wild = action(CardColor.WILD, CardType.WILD);
        Card skip = action(CardColor.RED, CardType.SKIP);
        Deck deck = deckInDrawOrder(GameMode.CLASSIC, List.of(wild, skip));
        Map<String, Long> before = cardMultiset(deck.getDrawPile());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gameService.drawInitialNumberCard(deck)
        );

        assertTrue(error.getMessage().contains("colored number card"));
        assertEquals(before, cardMultiset(deck.getDrawPile()));
        assertTrue(deck.getDiscardPile().isEmpty());
    }

    private void assertSelectionConservesCards(Map<String, Long> before, Card selected, Deck deck) {
        List<Card> after = new ArrayList<>(deck.getDrawPile());
        after.add(selected);
        assertEquals(before, cardMultiset(after));
        assertTrue(selected.color() != null && selected.color() != CardColor.WILD);
        assertEquals(CardType.NUMBER, selected.type());
    }

    private void assertContainsSameInstance(List<Card> cards, Card expected) {
        assertTrue(cards.stream().anyMatch(card -> card == expected));
    }

    private Deck deckInDrawOrder(GameMode mode, List<Card> drawOrder) {
        List<Card> drawPile = new ArrayList<>(drawOrder);
        Collections.reverse(drawPile);
        return new Deck(drawPile, List.of(), mode);
    }

    private Map<String, Long> cardMultiset(List<Card> cards) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Card card : cards) {
            counts.merge(cardKey(card), 1L, Long::sum);
        }
        return counts;
    }

    private String cardKey(Card card) {
        return card.color() + "|" + card.type() + "|" + card.value();
    }

    private List<Card> readCards(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Card>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private GamePlayer player(Game game, User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(List.of());
        return player;
    }

    private Card number(CardColor color, int value) {
        return new Card(color, CardType.NUMBER, value);
    }

    private Card action(CardColor color, CardType type) {
        return new Card(color, type, 20);
    }
}
