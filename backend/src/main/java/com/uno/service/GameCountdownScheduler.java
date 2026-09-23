package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.enums.GameStatus;
import com.uno.repository.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class GameCountdownScheduler {

    private static final Logger log = LoggerFactory.getLogger(GameCountdownScheduler.class);

    private final GameRepository gameRepository;
    private final GameService gameService;

    public GameCountdownScheduler(GameRepository gameRepository, GameService gameService) {
        this.gameRepository = gameRepository;
        this.gameService = gameService;
    }

    @Scheduled(fixedDelayString = "${uno.game-countdown-check-ms:500}")
    public void finishExpiredGames() {
        List<Long> expiredGameIds = gameRepository
                .findByStatusAndCountdownEndsAtLessThanEqual(GameStatus.PLAYING, LocalDateTime.now())
                .stream()
                .map(Game::getId)
                .toList();

        for (Long gameId : expiredGameIds) {
            try {
                gameService.expireCountdown(gameId);
            } catch (RuntimeException error) {
                log.error("[UNO] countdown check failed gameId={}", gameId, error);
            }
        }
    }
}
