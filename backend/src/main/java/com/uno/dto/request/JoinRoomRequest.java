package com.uno.dto.request;

import jakarta.validation.constraints.NotBlank;

public class JoinRoomRequest {
    @NotBlank(message = "房间码不能为空")
    private String roomCode;

    // Getters and Setters
    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) { this.roomCode = roomCode; }
}
