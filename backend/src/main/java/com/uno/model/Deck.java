package com.uno.model;

import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Deck {

    private List<Card> drawPile = new ArrayList<>();
    private List<Card> discardPile = new ArrayList<>();

    public Deck() {
        this(GameMode.CLASSIC);
    }

    public Deck(GameMode gameMode) {
        initializeDeck(gameMode == null ? GameMode.CLASSIC : gameMode);
        shuffle();
    }

    public Deck(List<Card> drawPile, List<Card> discardPile) {
        this(drawPile, discardPile, GameMode.CLASSIC);
    }

    public Deck(List<Card> drawPile, List<Card> discardPile, GameMode gameMode) {
        this.drawPile = new ArrayList<>();
        this.discardPile = new ArrayList<>();

        for (Card card : drawPile == null ? List.<Card>of() : drawPile) {
            if (card != null && card.color() != null && card.type() != null) {
                this.drawPile.add(card);
            }
        }
        for (Card card : discardPile == null ? List.<Card>of() : discardPile) {
            if (card != null && card.color() != null && card.type() != null) {
                this.discardPile.add(card);
            }
        }

        if (this.drawPile.isEmpty()) {
            initializeDeck(gameMode == null ? GameMode.CLASSIC : gameMode);
            shuffle();
        }
    }

    private void initializeDeck(GameMode gameMode) {
        initializeClassicDeck();
        if (gameMode == GameMode.NO_MERCY) {
            addNoMercyCards();
        }
    }

    private void initializeClassicDeck() {
        List<CardColor> colors = List.of(CardColor.RED, CardColor.BLUE, CardColor.GREEN, CardColor.YELLOW);

        for (CardColor color : colors) {
            drawPile.add(new Card(color, CardType.NUMBER, 0));
            for (int value = 1; value <= 9; value++) {
                drawPile.add(new Card(color, CardType.NUMBER, value));
                drawPile.add(new Card(color, CardType.NUMBER, value));
            }
            for (int i = 0; i < 2; i++) {
                drawPile.add(new Card(color, CardType.SKIP, 20));
                drawPile.add(new Card(color, CardType.REVERSE, 20));
                drawPile.add(new Card(color, CardType.DRAW_TWO, 20));
            }
        }

        for (int i = 0; i < 4; i++) {
            drawPile.add(new Card(CardColor.WILD, CardType.WILD, 50));
            drawPile.add(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50));
        }
    }

    private void addNoMercyCards() {
        List<CardColor> colors = List.of(CardColor.RED, CardColor.BLUE, CardColor.GREEN, CardColor.YELLOW);

        for (CardColor color : colors) {
            for (int i = 0; i < 2; i++) {
                // No Mercy test deck: 2 extra colored +2 cards per color.
                drawPile.add(new Card(color, CardType.DRAW_TWO, 20));
                // No Mercy test deck: 2 colored +4 cards per color.
                drawPile.add(new Card(color, CardType.DRAW_FOUR, 40));
                // No Mercy test deck: 2 discard-all-color cards per color.
                drawPile.add(new Card(color, CardType.DISCARD_ALL_COLOR, 20));
                // No Mercy test deck: 2 skip-all cards per color.
                drawPile.add(new Card(color, CardType.SKIP_ALL, 20));
            }
        }

        for (int i = 0; i < 4; i++) {
            // No Mercy test deck: 4 wild +6 cards.
            drawPile.add(new Card(CardColor.WILD, CardType.WILD_DRAW_SIX, 60));
            // No Mercy test deck: 4 wild +10 cards.
            drawPile.add(new Card(CardColor.WILD, CardType.WILD_DRAW_TEN, 100));
            // No Mercy test deck: 4 wild reverse +4 cards.
            drawPile.add(new Card(CardColor.WILD, CardType.WILD_REVERSE_DRAW_FOUR, 50));
        }
    }

    public void shuffle() {
        Collections.shuffle(drawPile);
    }

    public Card drawCard() {
        if (drawPile.isEmpty()) {
            recycleDiscardPile();
        }
        if (drawPile.isEmpty()) {
            return null;
        }
        return drawPile.remove(drawPile.size() - 1);
    }

    public void discard(Card card) {
        discardPile.add(card);
    }

    public Card getTopDiscard() {
        if (discardPile.isEmpty()) {
            return null;
        }
        return discardPile.get(discardPile.size() - 1);
    }

    private void recycleDiscardPile() {
        if (discardPile.size() <= 1) {
            return;
        }
        Card top = discardPile.remove(discardPile.size() - 1);
        drawPile.addAll(discardPile);
        discardPile.clear();
        discardPile.add(top);
        shuffle();
    }

    public List<Card> getDrawPile() { return drawPile; }
    public List<Card> getDiscardPile() { return discardPile; }
    public int getDrawPileSize() { return drawPile.size(); }
}
