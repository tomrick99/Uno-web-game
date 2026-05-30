package com.uno.repository;

import com.uno.entity.Game;
import com.uno.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
    List<Game> findByRoom(Room room);
    List<Game> findByStatus(com.uno.entity.enums.GameStatus status);
}
