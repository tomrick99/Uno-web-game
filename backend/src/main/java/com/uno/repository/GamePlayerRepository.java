package com.uno.repository;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GamePlayerRepository extends JpaRepository<GamePlayer, Long> {
    List<GamePlayer> findByGame(Game game);
    List<GamePlayer> findByGameOrderBySeatIndexAsc(Game game);
    Optional<GamePlayer> findByGameAndUser(Game game, User user);
    void deleteAllByGame(Game game);
}
