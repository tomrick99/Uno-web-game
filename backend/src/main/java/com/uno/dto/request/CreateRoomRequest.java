package com.uno.dto.request;

import com.uno.entity.enums.GameMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class CreateRoomRequest {
    @Min(value = 2, message = "At least 2 players are required")
    @Max(value = 8, message = "At most 8 players are allowed")
    private int maxPlayers = 2;

    @Min(value = 8, message = "At least 8 rounds are required")
    @Max(value = 32, message = "At most 32 rounds are allowed")
    private int totalRounds = 8;

    @Min(value = 5, message = "At least 5 minutes are required")
    @Max(value = 15, message = "At most 15 minutes are allowed")
    private int roundTimeLimitMinutes = 10;

    private GameMode gameMode = GameMode.CLASSIC;

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public int getRoundTimeLimitMinutes() { return roundTimeLimitMinutes; }
    public void setRoundTimeLimitMinutes(int roundTimeLimitMinutes) { this.roundTimeLimitMinutes = roundTimeLimitMinutes; }

    public GameMode getGameMode() { return gameMode; }
    public void setGameMode(GameMode gameMode) { this.gameMode = gameMode; }
}
