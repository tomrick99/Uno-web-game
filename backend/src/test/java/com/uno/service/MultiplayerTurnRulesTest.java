package com.uno.service;

import com.uno.entity.Room;
import com.uno.entity.enums.CardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerTurnRulesTest {

    private final GameService gameService = new GameService(null, null, null, null, null, null);

    @Test
    void threePlayerRoomDoesNotStartUntilAllPlayersJoin() {
        Room room = new Room();
        room.setMaxPlayers(3);

        assertFalse(gameService.shouldStartGame(room, 1));
        assertFalse(gameService.shouldStartGame(room, 2));
        assertTrue(gameService.shouldStartGame(room, 3));
    }

    @Test
    void normalTurnFollowsSeatOrderInThreePlayerGame() {
        List<Long> players = List.of(10L, 20L, 30L);

        assertEquals(20L, gameService.previewNextTurn(players, 10L, true, CardType.NUMBER));
        assertEquals(30L, gameService.previewNextTurn(players, 20L, true, CardType.NUMBER));
        assertEquals(10L, gameService.previewNextTurn(players, 30L, true, CardType.NUMBER));
    }

    @Test
    void reverseUsesOppositeDirectionInThreePlayerGame() {
        List<Long> players = List.of(10L, 20L, 30L);

        assertEquals(30L, gameService.previewNextTurn(players, 10L, true, CardType.REVERSE));
        assertEquals(20L, gameService.previewNextTurn(players, 10L, false, CardType.REVERSE));
    }

    @Test
    void skipJumpsOnePlayerInCurrentDirection() {
        List<Long> players = List.of(10L, 20L, 30L);

        assertEquals(30L, gameService.previewNextTurn(players, 10L, true, CardType.SKIP));
        assertEquals(20L, gameService.previewNextTurn(players, 10L, false, CardType.SKIP));
    }

    @Test
    void noMercySpecialCardsChooseExpectedNextTurnInThreePlayerGame() {
        List<Long> players = List.of(10L, 20L, 30L);

        assertEquals(10L, gameService.previewNextTurn(players, 10L, true, CardType.SKIP_ALL));
        assertEquals(30L, gameService.previewNextTurn(players, 10L, true, CardType.WILD_REVERSE_DRAW_FOUR));
        assertEquals(20L, gameService.previewNextTurn(players, 10L, false, CardType.WILD_REVERSE_DRAW_FOUR));
    }
}
