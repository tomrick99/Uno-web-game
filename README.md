# UNO Web Game

Two-player online UNO game built with Spring Boot and static HTML/CSS/JavaScript pages.

## Tech Stack

- HTML
- CSS
- JavaScript
- Spring Boot
- REST API
- WebSocket / STOMP / SockJS
- MySQL

## Project Structure

- `backend/`: deployable Spring Boot application
- `backend/src/main/resources/static/`: frontend pages and assets served by Spring Boot
- `frontend/`: extra local copy of frontend files, not required for deployment

For Railway or Render, use `backend/` as the root directory.

## Features

- Two-player online UNO gameplay
- User login and registration
- Room creation and joining
- Real-time turn sync with WebSocket/STOMP
- Draw penalty stacking
- Return to lobby
- Rematch flow
- Admin room management

## Local Development

### 1. Create a MySQL database

Create a database such as `uno_db`.

### 2. Configure environment variables

You can use the variables from `.env.example` in your local shell or IDE run configuration.

Recommended local profile:

```bash
SPRING_PROFILES_ACTIVE=local
MYSQL_URL=jdbc:mysql://localhost:3306/uno_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8
MYSQL_USER=root
MYSQL_PASSWORD=your_password
```

### 3. Run the app

```bash
cd backend
mvn spring-boot:run
```

Open:

- `http://localhost:8080/`
- or `http://localhost:8080/index.html`

## Package and Run

```bash
cd backend
mvn clean package
java -jar target/uno-backend-1.0.0.jar
```

## Deployment

### GitHub

1. Push the repository to GitHub.
2. Keep `backend/` as the deployable app directory.
3. Do not commit passwords or local-only secrets.

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

If Railway gives split MySQL variables, this project can also read:

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

1. Open two browser windows.
2. Player A logs in and creates a room.
3. Player B logs in and joins the room.
4. Start a game.
5. Play cards in both windows.
6. Verify real-time sync, end-game sync, return-to-lobby sync, and rematch behavior.

## Production Checklist

- `mvn clean package` succeeds
- `java -jar target/uno-backend-1.0.0.jar` starts successfully
- MySQL connection works
- Homepage opens online
- WebSocket connects online
- Two browsers stay synchronized
- No passwords are committed
- `target/` is ignored

## Future Improvements

- Better mobile UI polish
- Spectator mode or replay support
- Match history
- Stronger admin controls
- More deployment automation
