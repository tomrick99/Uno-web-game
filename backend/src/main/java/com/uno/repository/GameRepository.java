package com.uno.repository;

import com.uno.entity.Game;
import com.uno.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
    List<Game> findByRoom(Room room);
    List<Game> findByStatus(com.uno.entity.enums.GameStatus status);

    @Query("select game.room.id from Game game where game.id = :gameId")
    Optional<Long> findRoomIdByGameId(@Param("gameId") Long gameId);
}
