package com.uno.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class CreateRoomRequest {
    @Min(value = 2, message = "最少 2 人")
    @Max(value = 4, message = "最多 4 人")
    private int maxPlayers = 2;

    // Getters and Setters
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
}
