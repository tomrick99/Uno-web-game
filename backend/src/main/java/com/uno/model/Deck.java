package com.uno.model;

import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;

import java.util.*;

/**
 * 牌堆模型 - 管理摸牌堆和弃牌堆
 */
public class Deck {

    private List<Card> drawPile = new ArrayList<>();
    private List<Card> discardPile = new ArrayList<>();

    public Deck() {
        initializeDeck();
        shuffle();
    }

    /**
     * 从已有牌堆创建 Deck（用于恢复游戏状态）
     */
    public Deck(List<Card> drawPile, List<Card> discardPile) {
        this.drawPile = new ArrayList<>();
        this.discardPile = new ArrayList<>();
        // 只保留有效的牌（color 不为 null）
        for (Card card : drawPile) {
            if (card != null && card.color() != null && card.type() != null) {
                this.drawPile.add(card);
            }
        }
        for (Card card : discardPile) {
            if (card != null && card.color() != null && card.type() != null) {
                this.discardPile.add(card);
            }
        }
        // 如果恢复的牌堆为空，初始化新牌堆
        if (this.drawPile.isEmpty()) {
            initializeDeck();
            shuffle();
        }
    }

    /**
     * 初始化 108 张 Uno 牌
     */
    private void initializeDeck() {
        List<CardColor> colors = List.of(CardColor.RED, CardColor.BLUE, CardColor.GREEN, CardColor.YELLOW);

        for (CardColor color : colors) {
            // 每种颜色 1 张 0，2 张 1-9
            drawPile.add(new Card(color, CardType.NUMBER, 0));
            for (int v = 1; v <= 9; v++) {
                drawPile.add(new Card(color, CardType.NUMBER, v));
                drawPile.add(new Card(color, CardType.NUMBER, v));
            }
            // 每种颜色 2 张功能牌
            for (int i = 0; i < 2; i++) {
                drawPile.add(new Card(color, CardType.SKIP, 20));
                drawPile.add(new Card(color, CardType.REVERSE, 20));
                drawPile.add(new Card(color, CardType.DRAW_TWO, 20));
            }
        }

        // 4 张 Wild，4 张 Wild Draw Four
        for (int i = 0; i < 4; i++) {
            drawPile.add(new Card(CardColor.WILD, CardType.WILD, 50));
            drawPile.add(new Card(CardColor.WILD, CardType.WILD_DRAW_FOUR, 50));
        }
    }

    /**
     * 洗牌
     */
    public void shuffle() {
        Collections.shuffle(drawPile);
    }

    /**
     * 摸一张牌
     */
    public Card drawCard() {
        if (drawPile.isEmpty()) {
            recycleDiscardPile();
        }
        if (drawPile.isEmpty()) {
            return null;  // 极端情况：无牌可摸
        }
        return drawPile.remove(drawPile.size() - 1);
    }

    /**
     * 打出一张牌到弃牌堆
     */
    public void discard(Card card) {
        discardPile.add(card);
    }

    /**
     * 获取弃牌堆顶部牌
     */
    public Card getTopDiscard() {
        if (discardPile.isEmpty()) return null;
        return discardPile.get(discardPile.size() - 1);
    }

    /**
     * 弃牌堆重新洗入摸牌堆
     */
    private void recycleDiscardPile() {
        if (discardPile.size() <= 1) return;  // 保留顶部一张
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
