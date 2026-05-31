package com.uno.model;

import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameMode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckGenerationTest {

    private static final Set<CardType> NO_MERCY_ONLY_TYPES = Set.of(
            CardType.DRAW_FOUR,
            CardType.DISCARD_ALL_COLOR,
            CardType.SKIP_ALL,
            CardType.WILD_DRAW_SIX,
            CardType.WILD_DRAW_TEN,
            CardType.WILD_REVERSE_DRAW_FOUR
    );

    @Test
    void classicDeckDoesNotContainNoMercyCards() {
        Set<CardType> types = new Deck(GameMode.CLASSIC).getDrawPile().stream()
                .map(Card::type)
                .collect(Collectors.toSet());

        for (CardType type : NO_MERCY_ONLY_TYPES) {
            assertFalse(types.contains(type), "Classic deck should not contain " + type);
        }
    }

    @Test
    void noMercyDeckContainsEveryNoMercyCardType() {
        Set<CardType> types = new Deck(GameMode.NO_MERCY).getDrawPile().stream()
                .map(Card::type)
                .collect(Collectors.toSet());

        for (CardType type : NO_MERCY_ONLY_TYPES) {
            assertTrue(types.contains(type), "No Mercy deck should contain " + type);
        }
    }

    @Test
    void noMercyDeckAddsExpectedTestCardCounts() {
        Deck deck = new Deck(GameMode.NO_MERCY);

        assertEquals(16, count(deck, CardType.DRAW_TWO));
        assertEquals(8, count(deck, CardType.DRAW_FOUR));
        assertEquals(8, count(deck, CardType.DISCARD_ALL_COLOR));
        assertEquals(8, count(deck, CardType.SKIP_ALL));
        assertEquals(4, count(deck, CardType.WILD_DRAW_SIX));
        assertEquals(4, count(deck, CardType.WILD_DRAW_TEN));
        assertEquals(4, count(deck, CardType.WILD_REVERSE_DRAW_FOUR));
    }

    private long count(Deck deck, CardType type) {
        return deck.getDrawPile().stream()
                .filter(card -> card.type() == type)
                .count();
    }
}
