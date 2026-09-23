package com.uno.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.uno.entity.enums.DrawPileRule;
import com.uno.entity.enums.GameMode;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "room")
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", unique = true, nullable = false, length = 10)
    private String roomCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "host_id", nullable = false)
    @JsonIgnoreProperties({"password", "createdAt"})
    private User host;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private com.uno.entity.enums.RoomStatus status = com.uno.entity.enums.RoomStatus.WAITING;

    @Column(name = "max_players")
    private int maxPlayers = 2;

    @Column(name = "total_rounds")
    private int totalRounds = 8;

    @Column(name = "round_time_limit_minutes")
    private int roundTimeLimitMinutes = 10;

    @Column(name = "countdown_enabled", nullable = false)
    private boolean countdownEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_mode", length = 50)
    private GameMode gameMode = GameMode.CLASSIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "draw_pile_rule", length = 50)
    private DrawPileRule drawPileRule = DrawPileRule.AUTO_REFILL;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (roomCode == null) {
            roomCode = generateRoomCode();
        }
        applyDefaults();
    }

    @PreUpdate
    protected void onUpdate() {
        applyDefaults();
    }

    private void applyDefaults() {
        if (maxPlayers < 2) {
            maxPlayers = 2;
        }
        if (totalRounds <= 0) {
            totalRounds = 8;
        }
        if (roundTimeLimitMinutes <= 0) {
            roundTimeLimitMinutes = 10;
        }
        if (gameMode == null) {
            gameMode = GameMode.CLASSIC;
        }
        if (gameMode == GameMode.CLASSIC || drawPileRule == null) {
            drawPileRule = DrawPileRule.AUTO_REFILL;
        }
    }

    private String generateRoomCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int idx = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(idx));
        }
        return sb.toString();
    }

    public Room() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

    public User getHost() { return host; }
    public void setHost(User host) { this.host = host; }

    public com.uno.entity.enums.RoomStatus getStatus() { return status; }
    public void setStatus(com.uno.entity.enums.RoomStatus status) { this.status = status; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public int getRoundTimeLimitMinutes() { return roundTimeLimitMinutes; }
    public void setRoundTimeLimitMinutes(int roundTimeLimitMinutes) { this.roundTimeLimitMinutes = roundTimeLimitMinutes; }

    public boolean isCountdownEnabled() { return countdownEnabled; }
    public void setCountdownEnabled(boolean countdownEnabled) { this.countdownEnabled = countdownEnabled; }

    public GameMode getGameMode() { return gameMode; }
    public void setGameMode(GameMode gameMode) { this.gameMode = gameMode; }

    public DrawPileRule getDrawPileRule() { return drawPileRule; }
    public void setDrawPileRule(DrawPileRule drawPileRule) { this.drawPileRule = drawPileRule; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
