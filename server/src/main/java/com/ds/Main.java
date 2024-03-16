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
                    Integer choice = Communication.receiveMessageInRange(reader, writer, 1, 2);
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
                                    1. List available books
                                    2. Search for a book
                                    3. View detailed information about book
                                    4. Add a book for lending
                                    5. Stop lending a book
                                    6. Borrow a book
                                    7. List borrow requests sent to you""");
                    Integer choice = Communication.receiveMessageInRange(reader, writer, 1, 1);
                    switch (choice) {
                        case 1:
                            listAllBooks(conn, writer, reader);
                            break;
                        case 2:
                            searchBook(conn, writer, reader);
                            break;
                        case 3:
                            viewBookDetails(conn, writer, reader);
                            break;
                        case 4:
                            addBook(conn, session, writer, reader);
                            break;
                        case 5:
                            removeBook(conn, session, writer, reader);
                            break;
                        case 6:
                            sendBorrowRequest(conn, session, writer, reader);
                            break;
                        case 7:
                            listReceivedBorrowRequests(conn, session, writer);
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
            st.setObject(1, session.id);
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
            st.setString(1, username);
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
            st.setString(1, username);
            st.setString(2, name);
            st.setString(3, password);
            st.setBoolean(4, isAdmin);
            st.executeUpdate();
        }
    }

    private static void login(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Your username");
        String username = Communication.receiveNonEmptyMessage(reader, writer);

        try (PreparedStatement st = conn.prepareStatement("SELECT id, password FROM AppUser WHERE username=?")) {
            st.setString(1, username);
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

    private static void listAllBooks(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        CommonMainLoopProcedures.listAvailableBooksByCondition(conn, writer, reader, "", new Binding[] {});
    }

    private static void searchBook(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "1. By title\n2. By author\n3. By genre");
        Integer choice = Communication.receiveMessageInRange(reader, writer, 1, 3);
        String filterOn = "title";
        if (choice == 2) {
            filterOn = "author";
        } else if (choice == 3) {
            filterOn = "genre";
        }
        Communication.sendMessage(writer, filterOn + " = ?");
        String filter = Communication.receiveNonEmptyMessage(reader, writer);
        CommonMainLoopProcedures.listAvailableBooksByCondition(
                conn,
                writer,
                reader,
                String.format("AND %s=?", filterOn),
                new Binding[] { (st) -> st.setString(1, filter) });
    }

    private static void viewBookDetails(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Book id");
        String id = Communication.receiveNonEmptyMessage(reader, writer);
        try (PreparedStatement st = conn.prepareStatement(
                CommonMainLoopProcedures.buildAvailableBooksQuery(
                        "Book.id, Book.title, Book.author, Book.genre, Book.description",
                        "AND id=?"))) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "404. No such book");
                    return;
                }
                Communication.sendMessage(writer, String.format("""
                        ID: %s
                        Title: %s
                        Genre: %s
                        Description: %s""", rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("genre"), rs.getString("description")));
            }
        }
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
            st.setObject(1, session.id);
            st.setString(2, title);
            st.setString(3, author);
            st.setString(4, genre);
            st.setString(5, description);
            st.executeUpdate();
        }
    }

    private static void removeBook(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Book id");
        String id = Communication.receiveNonEmptyMessage(reader, writer);
        try (PreparedStatement st = conn
                .prepareStatement(
                        CommonMainLoopProcedures.buildAvailableBooksQuery("Book.id", "AND Book.lenderId = ?"))) {
            st.setObject(1, session.id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "Can't delete this book");
                    return;
                }
            }
        }
        try (PreparedStatement st = conn.prepareStatement("DELETE FROM Book WHERE id = ? AND lenderId = ?")) {
            st.setString(1, id);
            st.setObject(2, session.id);
            int affected = st.executeUpdate();
            if (affected == 0) {
                Communication.sendMessage(writer, "Could not delete this book");
            }
        }
    }

    private static void sendBorrowRequest(Connection conn, Session session, BufferedWriter writer,
            BufferedReader reader) throws IOException, SQLException {
        Communication.sendMessage(writer, "Book id");
        String id = Communication.receiveNonEmptyMessage(reader, writer);
        // Ensure book exists and it is not owned by the current user
        try (PreparedStatement st = conn.prepareStatement("SELECT id, lenderId FROM Book WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "404. No such book");
                    return;
                }
                if (rs.getObject("lenderId", UUID.class).equals(session.id)) {
                    Communication.sendMessage(writer, "400. Can't borrow a book from yourself.");
                    return;
                }
            }
        }

        // Ensure there is no borrow request with status borrowed on this book
        try (PreparedStatement st = conn
                .prepareStatement("SELECT id from BookBorrowRequest WHERE bookId = ? AND status = 'BORROWED'")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    Communication.sendMessage(writer, "400. This book is already borrowed");
                    return;
                }
            }
        }

        // Insert BookBorrowRequest
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO BookBorrowRequest(bookId, borrowedId, status) VALUES (?, ?, 'PENDING')")) {
            st.setString(1, id);
            st.setObject(2, session.id);
            st.executeUpdate();
            Communication.sendMessage(writer, "Borrow request sent");
        }
    }

    private static void listReceivedBorrowRequests(Connection conn, Session session, BufferedWriter writer)
            throws IOException, SQLException {
        // id - from: xxxx - A Tale of Two Cities
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT id, Borrower.username AS borrowerUsername, Book.name as bookName
                FROM BookBorrowRequest
                LEFT JOIN AppUser AS Borrower ON Borrower.id = BookBorrowRequest.borrowerId
                LEFT JOIN Book ON Book.id = BookBorrowRequest.bookId WHERE Book.lenderId = ?""")) {
            st.setObject(1, session.id);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Communication.sendMessage(
                            writer,
                            String.format("%s - from: %s - %s",
                                    rs.getObject("id", UUID.class),
                                    rs.getString("borrowerUsername"),
                                    rs.getString("bookName")));
                }
            }
        }
    }
}
