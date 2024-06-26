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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class Session {
    public UUID userId;
}

public class Main {
    public static void main(String[] args) throws SQLException, NumberFormatException, IOException {
        final Map<String, String> env = System.getenv();

        // db connection properties
        Properties props = new Properties();
        props.setProperty("user", env.get("DB_USER"));
        props.setProperty("password", env.get("DB_PASSWORD"));
        final String url = String.format("jdbc:postgresql://%s/%s", env.get("DB_HOST"), env.get("DB_NAME"));

        ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BufferedWriter>> chatConnections = new ConcurrentHashMap<>();

        try (ServerSocket socket = new ServerSocket(Integer.parseInt(env.get("PORT")))) {
            System.err.println("Listening for connections...");
            while (true) {
                Socket client = socket.accept();
                System.err.println("Accepted client connection");
                // Per-client thread
                new Thread(() -> {
                    try (
                            Connection conn = DriverManager.getConnection(url, props);
                            BufferedWriter writer = new BufferedWriter(
                                    new OutputStreamWriter(client.getOutputStream()));
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(client.getInputStream()))) {
                        runClientLoop(conn, chatConnections, writer, reader);
                    } catch (IOException e) {
                        System.err.println("Failed to communicate with client: " + e.toString());
                        return;
                    } catch (SQLException e) {
                        System.err.println("Failed to create a db connection for client: " + e.toString());
                    } finally {
                        try {
                            client.close();
                            System.err.println("Closed the client connection");
                        } catch (IOException e) {
                            System.err.println("Failed to close connection for client: " + e);
                        }
                    }
                }).start();
            }
        }
    }

    private static void runClientLoop(
            Connection conn,
            ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BufferedWriter>> chatConnections,
            BufferedWriter writer,
            BufferedReader reader) throws IOException {
        Session session = new Session();
        while (true) {
            try {
                if (session.userId == null) {
                    showGuestMenu(conn, session, writer, reader);
                } else {
                    boolean isAdmin = false;

                    try (PreparedStatement st = conn
                            .prepareStatement("SELECT username, isAdmin from AppUser where id = ?")) {
                        MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.userId)));
                        try (ResultSet rs = st.executeQuery()) {
                            rs.next();
                            isAdmin = rs.getBoolean("isAdmin");
                            Communication.sendMessage(writer,
                                    "Logged in as: " + rs.getString("username") + (isAdmin ? " (admin)" : ""));
                        }
                    }

                    if (isAdmin) {
                        showAdminMenu(conn, writer, reader);
                    } else {
                        showUserMenu(conn, chatConnections, session, writer, reader);
                    }
                }
            } catch (SQLException e) {
                Communication.sendMessage(writer, "An internal error has occurred");
                e.printStackTrace();
            }
        }
    }

    private static void showGuestMenu(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "1. Register\n2. Login");
        int choice = Communication.receiveMessageInRange(reader, writer, 1, 2);
        if (choice == 1) {
            register(conn, writer, reader);
        } else {
            login(conn, session, writer, reader);
        }
    }

    private static void showAdminMenu(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(
                writer,
                """
                        1. List borrowed books
                        2. List available books
                        3. List borrow requests
                        4. Log out""");
        int choice = Communication.receiveMessageInRange(reader, writer, 1, 4);
        switch (choice) {
            case 1:
                adminListBorrowedBooks(conn, writer);
                break;
            case 2:
                adminListAvailableBooks(conn, writer);
                break;
            case 3:
                adminListBookBorrowRequests(conn, writer);
                break;
            default:
                break;
        }
    }

    private static void showUserMenu(
            Connection conn,
            ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BufferedWriter>> chatConnections,
            Session session,
            BufferedWriter writer,
            BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(
                writer,
                """
                        1. List available books
                            - You can view book details and borrow them here
                        2. Search for a book
                            - You can also view book details and borrow them here
                        3. Add a book for lending
                        4. List lent books
                            - These are the books you have lent to the bookstore
                            - You can remove a book here
                        5. List borrow requests sent to you
                            - You can accept/reject requests here
                            - You can chat with users that have borrowed your books here
                        6. List borrow requests you sent
                            - You can return borrowed books here
                            - You can chat with users from whom you have borrowed books here
                        7. Log out""");
        int choice = Communication.receiveMessageInRange(reader, writer, 1, 7);
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
            case 4:
                listLentBooks(conn, session, writer, reader);
                break;
            case 5:
                listReceivedBorrowRequests(conn, chatConnections, session, writer, reader);
                break;
            case 6:
                listSentBorrowRequests(conn, chatConnections, session, writer, reader);
                break;
            case 7:
                logout(session);
                break;
            default:
                break;
        }
    }

    private static void register(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Your username");
        String username = Communication.receiveNonEmptyMessage(reader, writer);
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

        Communication.sendMessage(writer, "Your name");
        String name = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Your password");
        String password = Communication.receiveNonEmptyMessage(reader, writer);
        Communication.sendMessage(writer, "Are you an admin\n1. Yes\n2. No");
        boolean isAdmin = Communication.receiveMessageInRange(reader, writer, 1, 2) == 1;

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
                    Communication.sendMessage(writer, "401. Invalid password");
                    return;
                }

                session.userId = rs.getObject("id", UUID.class);
            }
        }
    }

    private static void listAllBooks(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        MainLoopCommons.listAvailableBooksByCondition(
                conn,
                session.userId,
                writer,
                reader,
                "",
                List.of());
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
                session.userId,
                writer,
                reader,
                String.format("AND %s=?", filterOn),
                List.of((i, st) -> st.setString(i, filter)));
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
        Communication.sendMessage(writer, "Book price");
        double price = Communication.receivePositiveDouble(reader, writer);

        try (PreparedStatement st = conn
                .prepareStatement("""
                        INSERT INTO Book(
                            lenderId, title, author, genre, description, price
                        ) VALUES (?, ?, ?, ?, ?, ?)""")) {
            MainLoopCommons.applyBindings(
                    st,
                    List.of(
                            (i, s) -> s.setObject(i, session.userId),
                            (i, s) -> s.setString(i, title),
                            (i, s) -> s.setString(i, author),
                            (i, s) -> s.setString(i, genre),
                            (i, s) -> s.setString(i, description),
                            (i, s) -> s.setDouble(i, price)));
            st.executeUpdate();
        }
    }

    private static void listLentBooks(Connection conn, Session session, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT Book.id, Book.title, Book.author, Book.genre, Book.price FROM Book
                WHERE Book.lenderId = ?""")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.userId)));
            ArrayList<UUID> bookIds = new ArrayList<>();
            int i = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    bookIds.add(rs.getObject("id", UUID.class));
                    Communication.sendMessage(writer,
                            String.format(
                                    "(%s) %s - By %s - %s - $%s",
                                    ++i,
                                    rs.getString("title"),
                                    rs.getString("author"),
                                    rs.getString("genre"),
                                    rs.getString("price")));

                }
            }
            if (i == 0)
                return;
            Communication.sendMessage(writer, "1. Delete book\n2. Back");
            int choice = Communication.receiveMessageInRange(reader, writer, 1, 2);
            if (choice == 1) {
                Communication.sendMessage(writer, "Enter the book number");
                int bookIndex = Communication.receiveMessageInRange(reader, writer, 1, i) - 1;
                removeBook(conn, bookIds.get(bookIndex));
            }
        }
    }

    private static void removeBook(Connection conn, UUID bookId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("DELETE FROM Book WHERE id = ?")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, bookId)));
            st.executeUpdate();
        }
    }

    private static void listReceivedBorrowRequests(
            Connection conn,
            ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BufferedWriter>> chatConnections,
            Session session,
            BufferedWriter writer,
            BufferedReader reader) throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT
                    BookBorrowRequest.id,
                    BookBorrowRequest.status,
                    Borrower.id AS borrowerId,
                    Borrower.username AS borrowerUsername,
                    book.title AS bookName
                FROM BookBorrowRequest
                LEFT JOIN Book ON Book.id = BookBorrowRequest.bookId
                LEFT JOIN AppUser AS Borrower ON Borrower.id = BookBorrowRequest.borrowerId
                WHERE Book.lenderId = ?""")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.userId)));

            ArrayList<UUID> requestIds = new ArrayList<>();
            ArrayList<UUID> userIds = new ArrayList<>();
            ArrayList<String> statuses = new ArrayList<>();
            int i = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    requestIds.add(rs.getObject("id", UUID.class));
                    userIds.add(rs.getObject("borrowerId", UUID.class));
                    statuses.add(rs.getString("status"));
                    Communication.sendMessage(
                            writer,
                            String.format("(%s) from: %s - %s (%s)",
                                    ++i,
                                    rs.getString("borrowerUsername"),
                                    rs.getString("bookName"),
                                    rs.getString("status")));
                }
            }
            if (i == 0)
                return;
            Communication.sendMessage(writer, "1. Accept request\n2. Reject request\n3. Chat with user\n4. Back");
            int choice = Communication.receiveMessageInRange(reader, writer, 1, 4);
            if (choice != 4) {
                Communication.sendMessage(writer, "Enter the request number");
                int requestIndex = Communication.receiveMessageInRange(reader, writer, 1, i) - 1;
                if (choice == 1 || choice == 2) {
                    if (!statuses.get(requestIndex).equals("PENDING")) {
                        Communication.sendMessage(writer, "400. This request is not pending");
                        return;
                    }
                    updateBorrowRequestStatus(conn, requestIds.get(requestIndex),
                            choice == 1 ? "BORROWED" : "REJECTED");
                } else {
                    if (!statuses.get(requestIndex).equals("BORROWED")) {
                        Communication.sendMessage(writer, "400. This request's status is not 'BORROWED'");
                        return;
                    }
                    MainLoopCommons.chatWithUser(
                            conn,
                            chatConnections,
                            writer,
                            reader,
                            session.userId,
                            userIds.get(requestIndex));
                }
            }
        }
    }

    private static void updateBorrowRequestStatus(Connection conn, UUID requestId, String status) throws SQLException {
        try (PreparedStatement st = conn
                .prepareStatement(String.format("UPDATE BookBorrowRequest SET status = '%s' WHERE id = ?", status))) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, requestId)));
            st.executeUpdate();
        }
    }

    private static void listSentBorrowRequests(
            Connection conn,
            ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BufferedWriter>> chatConnections,
            Session session,
            BufferedWriter writer,
            BufferedReader reader) throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT
                    BookBorrowRequest.id,
                    BookBorrowRequest.status,
                    Lender.id AS lenderId,
                    Lender.username AS lenderUsername,
                    book.title AS bookName
                FROM BookBorrowRequest
                LEFT JOIN Book ON Book.id = BookBorrowRequest.bookId
                LEFT JOIN AppUser AS Lender ON Lender.id = Book.lenderId
                WHERE BookBorrowRequest.borrowerId = ?""")) {
            MainLoopCommons.applyBindings(st, List.of((i, s) -> s.setObject(i, session.userId)));

            ArrayList<UUID> requestIds = new ArrayList<>();
            ArrayList<UUID> userIds = new ArrayList<>();
            ArrayList<String> statuses = new ArrayList<>();
            int i = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    requestIds.add(rs.getObject("id", UUID.class));
                    userIds.add(rs.getObject("lenderId", UUID.class));
                    statuses.add(rs.getString("status"));
                    Communication.sendMessage(
                            writer,
                            String.format("(%s) to: %s - %s (%s)",
                                    ++i,
                                    rs.getString("lenderUsername"),
                                    rs.getString("bookName"),
                                    rs.getString("status")));
                }
            }
            if (i == 0)
                return;
            Communication.sendMessage(writer, "1. Return book\n2. Chat with user\n3. Back");
            int choice = Communication.receiveMessageInRange(reader, writer, 1, 3);
            if (choice != 3) {
                Communication.sendMessage(writer, "Enter the request number");
                int requestIndex = Communication.receiveMessageInRange(reader, writer, 1, i) - 1;

                if (!statuses.get(requestIndex).equals("BORROWED")) {
                    Communication.sendMessage(writer, "400. This request's status is not 'BORROWED'");
                    return;
                }

                if (choice == 1) {
                    updateBorrowRequestStatus(conn, requestIds.get(requestIndex), "RETURNED");
                } else {
                    MainLoopCommons.chatWithUser(
                            conn,
                            chatConnections,
                            writer,
                            reader,
                            session.userId,
                            userIds.get(requestIndex));
                }
            }
        }
    }

    private static void logout(Session session) {
        session.userId = null;
    }

    private static void adminListBorrowedBooks(Connection conn, BufferedWriter writer)
            throws IOException, SQLException {
        MainLoopCommons.listDetailedBooksByCondition(conn, writer, "WHERE BookBorrowRequest.status = 'BORROWED'");
    }

    private static void adminListAvailableBooks(Connection conn, BufferedWriter writer)
            throws IOException, SQLException {
        MainLoopCommons.listDetailedBooksByCondition(conn, writer,
                "WHERE BookBorrowRequest.status IS DISTINCT FROM 'BORROWED'");
    }

    private static void adminListBookBorrowRequests(Connection conn, BufferedWriter writer)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT
                    BookBorrowRequest.id,
                    book.title AS bookName,
                    Borrower.username AS borrowerUsername,
                    Lender.username AS lenderUsername,
                    BookBorrowRequest.status
                FROM BookBorrowRequest
                LEFT JOIN Book on Book.id = BookBorrowRequest.bookId
                LEFT JOIN AppUser AS Borrower ON BookBorrowRequest.borrowerId = Borrower.id
                LEFT JOIN AppUser AS Lender ON Book.lenderId = Lender.id""")) {
            try (ResultSet rs = st.executeQuery()) {
                int totalBorrowed = 0;
                int totalRejected = 0;
                int totalReturned = 0;
                int totalPending = 0;
                while (rs.next()) {
                    Communication.sendMessage(writer, String.format("""
                            ID: %s
                            Book name: %s
                            Borrower username: %s
                            Lender username: %s
                            Status: %s""", rs.getObject("id", UUID.class), rs.getString("bookName"),
                            rs.getString("borrowerUsername"), rs.getString("lenderUsername"), rs.getString("status")));
                    switch (rs.getString("status")) {
                        case "BORROWED":
                            ++totalBorrowed;
                            break;
                        case "REJECTED":
                            ++totalRejected;
                            break;
                        case "RETURNED":
                            ++totalReturned;
                            break;
                        case "PENDING":
                            ++totalPending;
                            break;
                        default:
                            break;
                    }
                    Communication.sendMessage(writer, "");
                }
                Communication.sendMessage(writer, String.format("""
                        Total borrowed: %s
                        Total rejected: %s
                        Total returned: %s
                        Total pending: %s""", totalBorrowed, totalRejected, totalReturned, totalPending));
            }
        }
    }
}
