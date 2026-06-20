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
    void plusTwoAcceptsEqualOrHigherClassicPenalty() {
        Card topCard = new Card(CardColor.RED, CardType.DRAW_TWO, 20);

        assertTrue(gameService.canStackClassicPenalty(new Card(CardColor.BLUE, CardType.DRAW_TWO, 20), topCard));
        assertTrue(gameService.canStackClassicPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50), topCard));
        assertFalse(gameService.canStackClassicPenalty(new Card(CardColor.RED, CardType.SKIP, 20), topCard));
    }

    @Test
    void plusFourRejectsSmallerClassicPenalty() {
        Card topCard = new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50);

        assertTrue(gameService.canStackClassicPenalty(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50), topCard));
        assertFalse(gameService.canStackClassicPenalty(new Card(CardColor.RED, CardType.DRAW_TWO, 20), topCard));
    }
}
