package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.enums.GameStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameServiceTransactionOrderingTest {

    @Test
    void actionTransactionStartsAfterTheGameHasBeenMappedToItsRoom() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        Game game = new Game();
        game.setId(20L);
        game.setStatus(GameStatus.WAITING);

        when(gameRepository.findRoomIdByGameId(game.getId())).thenReturn(Optional.of(10L));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);

        GameService service = new GameService(
                gameRepository,
                playerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService,
                transactionManager);

        assertThrows(IllegalArgumentException.class,
                () -> service.playCard(game.getId(), 1L, 0, null));

        InOrder order = inOrder(gameRepository, transactionManager);
        order.verify(gameRepository).findRoomIdByGameId(game.getId());
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(gameRepository).findById(game.getId());
        order.verify(transactionManager).rollback(transactionStatus);
    }
}
