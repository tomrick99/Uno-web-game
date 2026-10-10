package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.GamePlayerRepository;
import com.uno.websocket.GameWebSocketService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class LobbyActivityNotificationService {

    private final GamePlayerRepository gamePlayerRepository;
    private final GameWebSocketService webSocketService;

    public LobbyActivityNotificationService(GamePlayerRepository gamePlayerRepository,
                                            GameWebSocketService webSocketService) {
        this.gamePlayerRepository = gamePlayerRepository;
        this.webSocketService = webSocketService;
    }

    public void notifyRoomActivity(Map<String, Object> roomState, String event, User actor) {
        if (actor == null || actor.getId() == null || actor.getUsername() == null) {
            return;
        }

        Set<Long> excludedUserIds = new LinkedHashSet<>();
        excludedUserIds.add(actor.getId());
        gamePlayerRepository.findAll().stream()
                .map(player -> player.getUser().getId())
                .forEach(excludedUserIds::add);

        webSocketService.broadcastLobbyActivityNotification(
                roomState,
                event,
                actor,
                excludedUserIds);
    }
}
