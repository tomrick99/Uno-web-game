package com.uno.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "game")
public class Game {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private com.uno.entity.enums.GameStatus status = com.uno.entity.enums.GameStatus.WAITING;

    @Column(name = "current_turn")
    private Long currentTurn;

    @Column(name = "direction")
    private boolean clockwise = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_color")
    private com.uno.entity.enums.CardColor currentColor;

    @Column(name = "pending_draw_count")
    private int pendingDrawCount = 0;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "pending_draw_type", length = 50)
    private com.uno.entity.enums.PendingDrawType pendingDrawType = com.uno.entity.enums.PendingDrawType.NONE;

    @Column(name = "last_penalty_player_id")
    private Long lastPenaltyPlayerId;

    @Column(name = "draw_pile", columnDefinition = "TEXT")
    private String drawPileJson;

    @Column(name = "discard_pile", columnDefinition = "TEXT")
    private String discardPileJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL)
    private List<GamePlayer> players = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (pendingDrawType == null) {
            pendingDrawType = com.uno.entity.enums.PendingDrawType.NONE;
        }
    }

    public Game() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public com.uno.entity.enums.GameStatus getStatus() { return status; }
    public void setStatus(com.uno.entity.enums.GameStatus status) { this.status = status; }

    public Long getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(Long currentTurn) { this.currentTurn = currentTurn; }

    public boolean isClockwise() { return clockwise; }
    public void setClockwise(boolean clockwise) { this.clockwise = clockwise; }

    public com.uno.entity.enums.CardColor getCurrentColor() { return currentColor; }
    public void setCurrentColor(com.uno.entity.enums.CardColor currentColor) { this.currentColor = currentColor; }

    public int getPendingDrawCount() { return pendingDrawCount; }
    public void setPendingDrawCount(int pendingDrawCount) { this.pendingDrawCount = pendingDrawCount; }

    public com.uno.entity.enums.PendingDrawType getPendingDrawType() { return pendingDrawType; }
    public void setPendingDrawType(com.uno.entity.enums.PendingDrawType pendingDrawType) { this.pendingDrawType = pendingDrawType; }

    public Long getLastPenaltyPlayerId() { return lastPenaltyPlayerId; }
    public void setLastPenaltyPlayerId(Long lastPenaltyPlayerId) { this.lastPenaltyPlayerId = lastPenaltyPlayerId; }

    public String getDrawPileJson() { return drawPileJson; }
    public void setDrawPileJson(String drawPileJson) { this.drawPileJson = drawPileJson; }

    public String getDiscardPileJson() { return discardPileJson; }
    public void setDiscardPileJson(String discardPileJson) { this.discardPileJson = discardPileJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<GamePlayer> getPlayers() { return players; }
    public void setPlayers(List<GamePlayer> players) { this.players = players; }
}
