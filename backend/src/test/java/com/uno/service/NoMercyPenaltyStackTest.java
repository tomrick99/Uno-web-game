package com.uno.service;

import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.model.Card;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoMercyPenaltyStackTest {

    private final GameService gameService = new GameService(null, null, null, null, null, null);

    @Test
    void plusFourCannotBeStackedWithPlusTwo() {
        Card topCard = new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50);

        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_TWO, 20), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_FOUR, 40), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_TEN, 100), topCard));
    }

    @Test
    void plusSixCanOnlyBeStackedWithSixOrHigher() {
        Card topCard = new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60);

        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_TWO, 20), topCard));
        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_FOUR, 40), topCard));
        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_TEN, 100), topCard));
    }

    @Test
    void plusTenCanOnlyBeStackedWithPlusTen() {
        Card topCard = new Card(CardColor.WILD, CardType.WILD_DRAW_TEN, 100);

        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_TWO, 20), topCard));
        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.RED, CardType.DRAW_FOUR, 40), topCard));
        assertFalse(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60), topCard));
        assertTrue(gameService.canStackNoMercyPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_TEN, 100), topCard));
    }
}
