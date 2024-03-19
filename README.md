# Author

Omar Adel Abdel Hamid Ahmed Brikaa - 20206043 - S5 - brikaaomar@gmail.com

# Running using Docker and Bash (recommended)

## Pre-requisites

- Docker
- Docker Compose
- Bash (on Windows you can use Git Bash, cygwin or WSL)
- GNU Make

## Steps

To start three clients and the server, use the following command:

```bash
make start
```

To attach to any of the three clients, use:

```bash
./attach <number of the client>
```

For example:

```bash
./attach 1
```

To stop, use:

```bash
make stop
```

# Running using Docker without Bash

## Pre-requisites

Same as above but without Bash and Make

## Steps

To start three clients and the server, use the following command:

```bash
docker compose up --build -d --scale ds-client=3
```

To view the logs:

```bash
docker compose logs -f
```

To attach to any of the three clients:

```bash
docker ps # note the container name or id of the target client
docker attach <client container name>
```

To stop:

```bash
docker compose stop
```

# Decisions

- The server is responsible for driving the communication (not just reacting to the client's requests like RMI)
- User gets to choose whether they are an admin on registration
- Description is added to the book details. It is what differentiates the books listing from the book details
- `ACCEPTED` borrow request status is split into `BORROWED` and `RETURNED`
- Two users can chat with each other only if there is an `BORROWED` borrow request between them
- Chatting between users is real-time when the two users have their chat opened
- "Browse through the bookstore's catalog" means list all available books for borrowing
  - The following books are not available for borrowing and hence will not be shown to the browsing user:
    - A book that is already borrowed
    - A book that is uploaded by the browsing user
    - A book that the browsing user already sent a borrow request for
