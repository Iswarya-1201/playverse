# PlayVerse — Play. Learn. Level Up.

A polished, responsive real-time multiplayer quiz game built with Java 21, Spring Boot, WebSocket, HTML, CSS, and JavaScript.

## Features
- Create a room and share the 5-character room code.
- Friends can join from different devices using the same public URL.
- Server-controlled question countdown (`endsAt`) is shared by all clients, so the timer does not independently start at `00:00` on a joining laptop.
- Up to 8 players per room, 10 randomized questions per round, speed-based scoring, and a final leaderboard.
- Responsive layout for desktop and mobile.
- No database configuration required for this starter build.

## Run locally
Requirements: JDK 21+ and Maven 3.9+.

```bash
mvn spring-boot:run
```

Open http://localhost:8080 in two browser windows to test. For two different laptops on different networks, deploy the app to a public host such as Render and share the deployed URL.

## Build a JAR
```bash
mvn clean package
java -jar target/playverse-1.0.0.jar
```

## Deploy on Render with Docker
1. Push this project to a GitHub repository.
2. Create a Render **Web Service** from that repository and choose **Docker**.
3. Render will build using `Dockerfile` and provide `PORT` automatically.
4. Open the public Render URL and share it with friends.

## Important notes
- Rooms, player scores, and quiz state are held in server memory and are cleared if the service restarts. This version does not persist user accounts or match history to MySQL.
- For production use, add authentication, rate limiting, stricter origin validation, and persistent storage.
- Never commit database passwords, API keys, or other secrets to GitHub.
