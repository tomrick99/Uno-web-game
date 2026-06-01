# UNO Web Game

Play online: https://uno-web-game-production.up.railway.app/

A browser-based UNO game built with Spring Boot, static HTML/CSS/JavaScript pages, WebSocket realtime sync, MySQL persistence, Classic rules, and No Mercy mode.

## Tech Stack

- HTML / CSS / JavaScript
- Vue 3 CDN pages
- Spring Boot
- REST API
- WebSocket / STOMP / SockJS
- MySQL
- Maven

## Project Structure

- `backend/`: deployable Spring Boot application
- `backend/src/main/resources/static/`: frontend pages and assets served by Spring Boot
- `frontend/`: local mirror of the static frontend files for editing/reference

For Railway or Render, use `backend/` as the deployable root directory.

## Features

- User registration, login, logout, and session-based auth
- Lobby with room list, custom room creation, joining, and return-to-lobby flow
- Custom room options:
  - `maxPlayers`: 2-8 players
  - `totalRounds`: 8, 16, or 32 rounds
  - `roundTimeLimitMinutes`: 5, 10, or 15 minutes
  - `gameMode`: Classic or No Mercy
- Multiplayer auto-start only when the room reaches `maxPlayers`
- Realtime game state sync with WebSocket/STOMP and polling fallback
- Classic UNO gameplay with color/number/type matching, skip, reverse, draw cards, wild cards, penalty stacking, game over, and rematch
- No Mercy mode with extra cards:
  - Colored `+4`
  - `DROP`
  - `SKIP ALL`
  - Black `+6`
  - Black `+10`
  - Black `+4 REV`
- No Mercy draw stacking: the next penalty card must be equal to or higher than the previous penalty value
- Click-to-open rules panel and recent game log
- Chinese/English language switch on lobby and game pages
- Admin room panel:
  - view all rooms
  - delete rooms
  - edit waiting-room configuration

## Local Development

### 1. Create a MySQL database

Create a database such as `uno_db`.

### 2. Configure environment variables

Use `.env.example` as a reference, then put the variables in your shell or IDE run configuration.

Recommended local profile:

```bash
SPRING_PROFILES_ACTIVE=local
MYSQL_URL=jdbc:mysql://localhost:3306/uno_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8
MYSQL_USER=root
MYSQL_PASSWORD=your_password
```

Do not commit real passwords.

### 3. Run the app

```bash
cd backend
mvn spring-boot:run
```

Open:

- `http://localhost:8080/`
- `http://localhost:8080/index.html`

## Package and Run

```bash
cd backend
mvn clean package
java -jar target/uno-backend-1.0.0.jar
```

## Database Notes

The project uses Hibernate `ddl-auto=update`, so local schema changes are usually applied automatically.

If an existing online database was created before custom rooms / No Mercy were added, verify that the `room` table has these columns:

```sql
ALTER TABLE room
ADD COLUMN total_rounds INT DEFAULT 8,
ADD COLUMN round_time_limit_minutes INT DEFAULT 10,
ADD COLUMN game_mode VARCHAR(50) DEFAULT 'CLASSIC';
```

`max_players` already existed in earlier versions; this version validates it as 2-8 players.

## Deployment

### GitHub

Before pushing:

```bash
git status
git add .
git commit -m "Fix play flow, admin room editing, and UI polish"
git push
```

Check that no passwords, local `.env` files, or database dumps are staged.

### Railway

- Root directory: `backend`
- Build command:

```bash
mvn clean package -DskipTests
```

- Start command:

```bash
java -jar target/uno-backend-1.0.0.jar
```

- Required environment variables:
  - `SPRING_PROFILES_ACTIVE=prod`
  - `MYSQL_URL` or Railway MySQL split variables
  - `MYSQL_USER`
  - `MYSQL_PASSWORD`
  - `PORT` is usually provided automatically by Railway

If Railway provides split MySQL variables, this project can also read:

- `MYSQLHOST`
- `MYSQLPORT`
- `MYSQLDATABASE`
- `MYSQLUSER`
- `MYSQLPASSWORD`

### Render

Recommended option: native Java/Maven deploy with `backend/` as root directory.

- Build command:

```bash
mvn clean package -DskipTests
```

- Start command:

```bash
java -jar target/uno-backend-1.0.0.jar
```

Alternative option: Docker deploy using `backend/Dockerfile`.

## Test Flow

### Classic

1. Open two browser windows.
2. Log in as two users.
3. Create a Classic room.
4. Join until the room reaches `maxPlayers`.
5. Play number cards, skip, reverse, `+2`, `+4`, wild color selection, return to lobby, and rematch.

### No Mercy

1. Create a No Mercy room.
2. Join with 2-4 browser sessions.
3. Verify new card display: `DROP`, `SKIP ALL`, `+6`, `+10`, `+4 REV`.
4. Verify black cards require color selection.
5. Verify draw stacking:
   - `+4` cannot be followed by `+2`
   - `+6` can be followed by `+6` or `+10`
   - `+10` can only be followed by `+10`

### Admin

1. Log in as username `admin`.
2. Open the admin panel from the lobby.
3. Edit a waiting room.
4. Confirm playing rooms cannot be edited.
5. Delete a test room.

## Verification

Backend tests:

```bash
cd backend
.\mvnw.cmd test
```

JavaScript syntax checks:

```bash
node --check backend/src/main/resources/static/js/pages/game.js
node --check backend/src/main/resources/static/js/pages/lobby.js
node --check backend/src/main/resources/static/js/pages/login.js
node --check backend/src/main/resources/static/js/pages/admin.js
```

## Production Checklist

- Maven build succeeds
- MySQL connection works
- Required `room` columns exist online
- Homepage opens online
- WebSocket connects online
- Two or more browsers stay synchronized
- Classic mode can still complete a game
- No Mercy mode displays and handles new cards
- Admin can edit waiting rooms and delete rooms
- No passwords are committed
- `target/` is ignored

## Future Improvements

- Full countdown behavior for `roundTimeLimitMinutes`
- Better mobile layout polish for very large hands
- Match history and player stats
- Stronger admin controls such as kick player or manual start
- Single-source frontend workflow instead of maintaining both static copies manually
