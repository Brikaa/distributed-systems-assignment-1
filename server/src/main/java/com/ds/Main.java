package com.ds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

class Session {
    public UUID id;
}

public class Main {
    public static void main(String[] args) throws SQLException, NumberFormatException, IOException {
        final Map<String, String> env = System.getenv();

        // Set up db connection
        Properties props = new Properties();
        props.setProperty("user", env.get("DB_USER"));
        props.setProperty("password", env.get("DB_PASSWORD"));
        final String url = String.format("jdbc:postgresql://%s/%s", env.get("DB_HOST"), env.get("DB_NAME"));
        Connection conn = DriverManager.getConnection(url, props);

        // Set up server socket
        ServerSocket socket = new ServerSocket(Integer.parseInt(env.get("PORT")));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Gracefully stopping the server...");
                socket.close();
                conn.close();
            } catch (IOException e) {
                System.out.println("Failed to close socket: " + e);
            } catch (SQLException e) {
                System.out.println("Failed to close DB connection: " + e);
            }
        }));

        // Connection accepting loop
        System.out.println("Listening for connections...");
        while (true) {
            try {
                Socket client = socket.accept();
                System.out.println("Accepted client connection");
                // Per-client thread
                Thread t = new Thread(() -> {
                    try (
                            BufferedWriter writer = new BufferedWriter(
                                    new OutputStreamWriter(client.getOutputStream()));
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(client.getInputStream()))) {
                        runClientLoop(conn, writer, reader);
                    } catch (IOException e) {
                        System.out.println("Failed to communicate with client: " + e.toString());
                        return;
                    } finally {
                        try {
                            client.close();
                            System.out.println("Closed the client connection");
                        } catch (IOException e) {
                            System.out.println("Failed to close connection for client: " + e);
                        }
                    }
                });

                t.start();
            } catch (IOException e) {
                System.out.println("Failed to listen for connections: " + e);
                break;
            }
        }
    }

    private static void runClientLoop(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException {
        Session session = new Session();
        while (true) {
            try {
                if (session.id == null) {
                    Communication.sendMessage(writer, "1. Register\n2. Login");
                    int choice = Communication.receiveMessageInRange(reader, writer, 1, 2);
                    if (choice == 1) {
                        register(conn, writer, reader);
                    } else {
                        login(conn, session, writer, reader);
                    }
                } else {
                    showLoggedInUser(conn, session, writer);
                    Communication.sendMessage(
                            writer,
                            """
                                    1. List available books (includes borrowing, details)
                                    2. Search for a book (includes borrowing, details)
                                    3. Add a book for lending
                                    4. List lent books (includes removing)
                                    4. Stop lending a book
                                    5. List borrow requests sent to you
                                    6. Accept/reject a borrow request
                                    7. List borrow requests you sent""");
                    int choice = Communication.receiveMessageInRange(reader, writer, 1, 1);
                    switch (choice) {
                        case 1:
                            listAllBooks(conn, session, writer, reader);
                            break;
                        case 2:
                            searchBook(conn, session, writer, reader);
                            break;
                        case 3:
                            addBook(conn, session, writer, reader);
                            break;
                        case 5:
                            removeBook(conn, session, writer, reader);
                            break;
                        case 6:
                            listReceivedBorrowRequests(conn, session, writer);
                            break;
                        case 7:
                            acceptOrRejectBorrowRequest(conn, session, writer, reader);
                            break;
                        case 8:
                            listSentBorrowRequests(conn, session, writer);
                            break;
                        default:
                            break;
                    }
                }
            } catch (SQLException e) {
                Communication.sendMessage(writer, "An internal error has occurred");
                e.printStackTrace();
            }
        }
    }

    private static void showLoggedInUser(Connection conn, Session session, BufferedWriter writer)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT username from AppUser where id = ?")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.id)));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                Communication.sendMessage(writer, "Logged in as: " + rs.getString("username"));
            }
        }
    }

    private static void register(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Your name");
        String name = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Your username");
        String username = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Your password");
        String password = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Are you an admin\n1. Yes\n2. No");
        boolean isAdmin = Communication.receiveMessageInRange(reader, writer, 1, 2) == 1;

        if (name.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Communication.sendMessage(writer, "Username, name and password can't be empty.");
            return;
        }

        // Check if user already exists
        try (PreparedStatement st = conn.prepareStatement("SELECT id FROM AppUser WHERE username=?")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setString(i, username)));
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    Communication.sendMessage(writer, "400. A user with this username already exists");
                    return;
                }
            }
        }

        // Insert user
        try (PreparedStatement st = conn
                .prepareStatement("INSERT INTO AppUser(username, name, password, isAdmin) VALUES (?, ?, ?, ?)")) {
            MainLoopCommons.applyBindings(
                    st, List.of(
                            (i, s) -> s.setString(i, username),
                            (i, s) -> s.setString(i, name),
                            (i, s) -> s.setString(i, password),
                            (i, s) -> s.setBoolean(i, isAdmin)));
            st.executeUpdate();
        }
    }

    private static void login(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Your username");
        String username = Communication.receiveNonEmptyMessage(reader, writer);

        try (PreparedStatement st = conn.prepareStatement("SELECT id, password FROM AppUser WHERE username=?")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setString(i, username)));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "404. Invalid username");
                    return;
                }

                Communication.sendMessage(writer, "Your password");
                String password = Communication.receiveNonEmptyMessage(reader, writer);
                if (!rs.getString("password").equals(password)) {
                    Communication.sendMessage(writer, "400. Invalid password");
                    return;
                }

                session.id = rs.getObject("id", UUID.class);
            }
        }
    }

    private static void listAllBooks(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        MainLoopCommons.listAvailableBooksByCondition(
                conn,
                session.id,
                writer,
                reader,
                "AND Book.lenderId != ?",
                List.of((i, st) -> st.setObject(i, session.id)));
    }

    private static void searchBook(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "1. By title\n2. By author\n3. By genre");
        int choice = Communication.receiveMessageInRange(reader, writer, 1, 3);
        String filterOn = "title";
        if (choice == 2) {
            filterOn = "author";
        } else if (choice == 3) {
            filterOn = "genre";
        }
        Communication.sendMessage(writer, filterOn + " = ?");
        String filter = Communication.receiveNonEmptyMessage(reader, writer);
        MainLoopCommons.listAvailableBooksByCondition(
                conn,
                session.id,
                writer,
                reader,
                String.format("AND %s=? AND Book.lenderId != ?", filterOn),
                List.of((i, st) -> st.setString(i, filter), (i, st) -> st.setObject(i, session.id)));
    }

    private static void addBook(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        // title, author, genre, description
        Communication.sendMessage(writer, "Book title");
        String title = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Book author");
        String author = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Book genre");
        String genre = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Book description");
        String description = Communication.receiveNonEmptyMessage(reader, writer);

        try (PreparedStatement st = conn
                .prepareStatement(
                        "INSERT INTO Book(lenderId, title, author, genre, description) VALUES (?, ?, ?, ?, ?)")) {
            MainLoopCommons.applyBindings(
                    st,
                    List.of(
                            (i, s) -> s.setObject(i, session.id),
                            (i, s) -> s.setString(i, title),
                            (i, s) -> s.setString(i, author),
                            (i, s) -> s.setString(i, genre),
                            (i, s) -> s.setString(i, description)));
            st.executeUpdate();
        }
    }

    private static void removeBook(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Book id");
        String id = Communication.receiveNonEmptyMessage(reader, writer);
        // Ensure it is not borrowed and that you own it
        try (PreparedStatement st = conn
                .prepareStatement(
                        MainLoopCommons.buildAvailableBooksQuery("Book.id", "AND Book.lenderId = ?"))) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.id)));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "Can't stop lending this book");
                    return;
                }
            }
        }
        try (PreparedStatement st = conn.prepareStatement("DELETE FROM Book WHERE id = ?")) {
            MainLoopCommons.applyBindings(st,
                    List.of((i, s) -> s.setString(i, id), (i, s) -> s.setObject(i, session.id)));
            st.executeUpdate();
        }
    }

    private static void listReceivedBorrowRequests(Connection conn, Session session, BufferedWriter writer)
            throws IOException, SQLException {
        MainLoopCommons.listBorrowRequestsByCondition(conn, session, writer, "Book.lenderId = ?",
                List.of((i, st) -> st.setObject(i, session.id)));
    }

    private static void acceptOrRejectBorrowRequest(Connection conn, Session session, BufferedWriter writer,
            BufferedReader reader) throws IOException, SQLException {
        Communication.sendMessage(writer, "Request id");
        String id = Communication.receiveNonEmptyMessage(reader, writer);
        String borrowerUsername = null;

        // Ensure this request exists and is addressed to the user
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT BookBorrowRequest.borrowerId, Borrower.username AS borrowerUsername
                FROM BookBorrowRequest
                LEFT JOIN Book ON Book.id = BookBorrowRequest.bookId
                LEFT JOIN AppUser AS Borrower ON AppUser.id = BookBorrowRequest.borrowerId
                WHERE BookBorrowRequest.id = ? AND Book.lenderId = ?""")) {

            MainLoopCommons.applyBindings(st, List.of(
                    (i, s) -> s.setString(i, id),
                    (i, s) -> s.setObject(i, session.id)));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "Can't accept/reject such a request");
                    return;
                }
                borrowerUsername = rs.getString(borrowerUsername);
            }
        }

        Communication.sendMessage(writer, "1. Accept\n2. Reject");
        String status = Communication.receiveMessageInRange(reader, writer, 1, 2) == 1 ? "BORROWED" : "REJECTED";

        try (PreparedStatement st = conn.prepareStatement("UPDATE BookBorrowRequest SET status = ? WHERE id = ?")) {
            MainLoopCommons.applyBindings(st, List.of(
                    (i, s) -> s.setString(i, status),
                    (i, s) -> s.setString(i, id)));
            st.executeUpdate();
            if (status == "BORROWED") {
                Communication.sendMessage(writer,
                        "Accepted the borrow request, you can now chat with user of username: " + borrowerUsername);
            }
        }
    }

    private static void listSentBorrowRequests(Connection conn, Session session, BufferedWriter writer)
            throws IOException, SQLException {
        MainLoopCommons.listBorrowRequestsByCondition(
                conn,
                session,
                writer,
                "BookBorrowRequest.borrowerId = ?",
                List.of((i, st) -> st.setObject(i, session.id)));
    }
}
