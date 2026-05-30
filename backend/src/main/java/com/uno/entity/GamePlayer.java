package com.uno.entity;

import com.uno.model.Card;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "game_player")
public class GamePlayer {
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "seat_index")
    private int seatIndex;  // 座位顺序（0, 1, 2...）

    @Column(name = "hand_cards", columnDefinition = "TEXT")
    private String handCardsJson;  // 手牌（JSON 数组）

    @Column(name = "said_uno")
    private boolean saidUno = false;

    @Column(name = "rematch_ready")
    private boolean rematchReady = false;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @PrePersist
    protected void onCreate() {
        joinedAt = LocalDateTime.now();
    }

    // 手牌操作方法
    public List<Card> getHandCards() {
        try {
            if (handCardsJson == null || handCardsJson.isEmpty()) {
                return new ArrayList<>();
            }
            List<Card> cards = mapper.readValue(handCardsJson, new TypeReference<List<Card>>() {});
            // 过滤掉反序列化后无效的牌
            List<Card> validCards = new ArrayList<>();
            for (Card card : cards) {
                if (card != null && card.color() != null && card.type() != null) {
                    validCards.add(card);
                }
            }
            return validCards;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void setHandCards(List<Card> cards) {
        try {
            this.handCardsJson = mapper.writeValueAsString(cards);
        } catch (Exception e) {
            this.handCardsJson = "[]";
        }
    }

    public void addCard(Card card) {
        List<Card> cards = getHandCards();
        cards.add(card);
        setHandCards(cards);
    }

    public void removeCard(Card card) {
        List<Card> cards = getHandCards();
        cards.remove(card);
        setHandCards(cards);
    }

    // Constructors
    public GamePlayer() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Game getGame() { return game; }
    public void setGame(Game game) { this.game = game; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public int getSeatIndex() { return seatIndex; }
    public void setSeatIndex(int seatIndex) { this.seatIndex = seatIndex; }

    public String getHandCardsJson() { return handCardsJson; }
    public void setHandCardsJson(String handCardsJson) { this.handCardsJson = handCardsJson; }

    public boolean isSaidUno() { return saidUno; }
    public void setSaidUno(boolean saidUno) { this.saidUno = saidUno; }

    public boolean isRematchReady() { return rematchReady; }
    public void setRematchReady(boolean rematchReady) { this.rematchReady = rematchReady; }

    public LocalDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
}
