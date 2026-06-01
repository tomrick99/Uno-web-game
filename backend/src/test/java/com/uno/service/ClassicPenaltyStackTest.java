package com.uno.service;

import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.model.Card;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassicPenaltyStackTest {

    private final GameService gameService = new GameService(null, null, null, null, null, null);

    @Test
    void drawTwoChainOnlyAcceptsDrawTwo() {
        assertTrue(gameService.canStackClassicDrawTwo(new Card(CardColor.RED, CardType.DRAW_TWO, 20)));
        assertFalse(gameService.canStackClassicDrawTwo(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50)));
        assertFalse(gameService.canStackClassicDrawTwo(new Card(CardColor.RED, CardType.SKIP, 20)));
    }

    @Test
    void wildDrawFourChainOnlyAcceptsWildDrawFour() {
        assertTrue(gameService.canStackClassicWildDrawFour(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50)));
        assertFalse(gameService.canStackClassicWildDrawFour(new Card(CardColor.RED, CardType.DRAW_TWO, 20)));
        assertFalse(gameService.canStackClassicWildDrawFour(new Card(CardColor.WILD, CardType.WILD, 50)));
    }
}
