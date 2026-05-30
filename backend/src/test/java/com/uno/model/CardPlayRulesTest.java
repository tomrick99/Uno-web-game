package com.uno.model;

import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardPlayRulesTest {

    @Test
    void sameColorNumberCardCanPlay() {
        Card topCard = new Card(CardColor.RED, CardType.NUMBER, 5);
        Card card = new Card(CardColor.RED, CardType.NUMBER, 9);

        assertTrue(card.canPlayOn(topCard, CardColor.RED));
    }

    @Test
    void sameNumberDifferentColorCanPlay() {
        Card topCard = new Card(CardColor.RED, CardType.NUMBER, 5);
        Card card = new Card(CardColor.BLUE, CardType.NUMBER, 5);

        assertTrue(card.canPlayOn(topCard, CardColor.RED));
    }

    @Test
    void differentColorAndNumberCannotPlay() {
        Card topCard = new Card(CardColor.RED, CardType.NUMBER, 5);
        Card card = new Card(CardColor.BLUE, CardType.NUMBER, 7);

        assertFalse(card.canPlayOn(topCard, CardColor.RED));
    }

    @Test
    void sameActionTypeCanPlay() {
        Card topCard = new Card(CardColor.RED, CardType.SKIP, 20);
        Card card = new Card(CardColor.BLUE, CardType.SKIP, 20);

        assertTrue(card.canPlayOn(topCard, CardColor.RED));
    }

    @Test
    void differentActionTypeCannotPlayWithoutColorMatch() {
        Card topCard = new Card(CardColor.RED, CardType.SKIP, 20);
        Card card = new Card(CardColor.BLUE, CardType.DRAW_TWO, 20);

        assertFalse(card.canPlayOn(topCard, CardColor.RED));
    }

    @Test
    void wildCardsCanAlwaysPlay() {
        Card topCard = new Card(CardColor.YELLOW, CardType.NUMBER, 2);
        Card wild = new Card(CardColor.WILD, CardType.WILD, 50);
        Card wildDrawFour = new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50);

        assertTrue(wild.canPlayOn(topCard, CardColor.YELLOW));
        assertTrue(wildDrawFour.canPlayOn(topCard, CardColor.YELLOW));
    }

    @Test
    void currentColorAfterWildControlsNextPlay() {
        Card topCard = new Card(CardColor.WILD, CardType.WILD, 50);
        Card redNumber = new Card(CardColor.RED, CardType.NUMBER, 9);
        Card blueNumber = new Card(CardColor.BLUE, CardType.NUMBER, 9);

        assertTrue(redNumber.canPlayOn(topCard, CardColor.RED));
        assertFalse(blueNumber.canPlayOn(topCard, CardColor.RED));
    }
}
