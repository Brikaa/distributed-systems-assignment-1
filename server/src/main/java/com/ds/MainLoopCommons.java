package com.ds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

interface Binding {
    void apply(int parameterIndex, PreparedStatement st) throws SQLException;
}

public class MainLoopCommons {

    public static void listAvailableBooksByCondition(
            Connection conn,
            UUID sessionId,
            BufferedWriter writer,
            BufferedReader reader,
            String extraConditions,
            List<Binding> bindings) throws IOException, SQLException {
        try (PreparedStatement st = conn
                .prepareStatement("""
                        SELECT Book.id, Book.title, Book.author, Book.genre, Book.description FROM Book
                        LEFT JOIN BookBorrowRequest ON Book.id = BookBorrowRequest.bookId
                        WHERE
                            Book.lenderId <> ?
                            AND BookBorrowRequest.status IS DISTINCT FROM 'BORROWED'
                            AND (
                                BookBorrowRequest.borrowerId <> ?
                                OR BookBorrowRequest.status IS DISTINCT FROM 'PENDING'
                            )""" + " " + extraConditions)) {
            bindings = new ArrayList<>(bindings);
            bindings.add(0, (i, s) -> s.setObject(i, sessionId));
            bindings.add(0, (i, s) -> s.setObject(i, sessionId));
            applyBindings(st, bindings);

            try (ResultSet rs = st.executeQuery()) {
                int i = 0;
                ArrayList<UUID> bookIds = new ArrayList<>();
                ArrayList<String> bookDetails = new ArrayList<>();
                while (rs.next()) {
                    bookIds.add(rs.getObject("id", UUID.class));
                    bookDetails.add(String.format("""
                            ID: %s
                            Author: %s
                            Title: %s
                            Genre: %s
                            Description: %s""",
                            rs.getObject("id", UUID.class),
                            rs.getString("author"),
                            rs.getString("title"),
                            rs.getString("genre"),
                            rs.getString("description")));
                    Communication.sendMessage(writer,
                            String.format(
                                    "%s. %s - By %s - %s",
                                    ++i,
                                    rs.getString("title"),
                                    rs.getString("author"),
                                    rs.getString("genre")));
                }
                if (i == 0)
                    return;
                Communication.sendMessage(writer, """
                        1. View detailed information about book
                        2. Send a borrow request
                        3. Back""");
                int choice = Communication.receiveMessageInRange(reader, writer, 1, 3);
                if (choice != 3) {
                    Communication.sendMessage(writer, "Enter the number of the book");
                    int bookIndex = Communication.receiveMessageInRange(reader, writer, 1, i) - 1;
                    if (choice == 1) {
                        Communication.sendMessage(writer, bookDetails.get(bookIndex));
                    } else {
                        sendBorrowRequest(conn, sessionId, bookIds.get(bookIndex));
                    }
                }
            }
        }
    }

    public static void applyBindings(PreparedStatement st, List<Binding> bindings) throws SQLException {
        int i = 1;
        for (Binding binding : bindings) {
            binding.apply(i++, st);
        }
    }

    private static void sendBorrowRequest(Connection conn, UUID sessionId, UUID bookId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO BookBorrowRequest(bookId, borrowerId, status) VALUES (?, ?, 'PENDING')")) {
            MainLoopCommons.applyBindings(st, List.of(
                    (i, s) -> s.setObject(i, bookId),
                    (i, s) -> s.setObject(i, sessionId)));
            st.executeUpdate();
        }
    }

    public static void chatWithUser(Connection conn, BufferedWriter writer, BufferedReader reader, UUID sourceId,
            UUID destinationId, String destinationUsername, String requestStatus) throws IOException, SQLException {
        if (!requestStatus.equals("BORROWED")) {
            Communication.sendMessage(writer, "400. This request's status is not 'BORROWED'");
            return;
        }
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT senderId, body FROM Message
                (senderId = ? AND receiverId = ?) OR (senderId = ? AND receiverId = ?)
                ORDER BY timestamp ASC""")) {
            applyBindings(st, List.of(
                    (i, s) -> s.setObject(i, sourceId),
                    (i, s) -> s.setObject(i, destinationId),
                    (i, s) -> s.setObject(i, destinationId),
                    (i, s) -> s.setObject(i, sourceId)));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    UUID senderId = rs.getObject("senderId", UUID.class);
                    String sourceName = senderId.equals(sourceId) ? "" : (destinationUsername + ": ");
                    Communication.sendMessage(writer, sourceName + rs.getString("body"));
                }
            }
        }
        while (true) {
            Communication.sendMessage(writer, "1. Send message\n2. Back");
            int choice = Communication.receiveMessageInRange(reader, writer, 1, 2);
            if (choice == 2) {
                break;
            }
            Communication.sendMessage(writer, "Message");
            String message = Communication.receiveNonEmptyMessage(reader, writer);
            sendMessage(conn, sourceId, destinationId, message);
        }
    }

    private static void sendMessage(Connection conn, UUID sourceId, UUID destinationId, String body)
            throws SQLException {
        try (PreparedStatement st = conn
                .prepareStatement("Insert INTO Message(senderId, receiverId, body, timestamp) VALUES (?, ?, ?, ?)")) {
            applyBindings(st, List.of(
                    (i, s) -> s.setObject(i, sourceId),
                    (i, s) -> s.setObject(i, destinationId),
                    (i, s) -> s.setString(i, body),
                    (i, s) -> s.setTimestamp(i, new Timestamp(System.currentTimeMillis()))));
            st.executeUpdate();
        }
    }

    public static void listDetailedBooksByCondition(Connection conn, BufferedWriter writer, String condition)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT
                    Book.id,
                    Book.title,
                    Book.author,
                    Book.genre,
                    Book.description,
                    AppUser.username AS lenderUsername
                FROM Book
                LEFT JOIN AppUser ON Book.lenderId = AppUser.id
                LEFT JOIN BookBorrowRequest ON BookBorrowRequest.bookId = Book.id""" + " " + condition)) {

            int total = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ++total;
                    Communication.sendMessage(writer,
                            String.format("""
                                    ID: %s
                                    Title: %s
                                    Author: %s
                                    Genre: %s
                                    Description: %s
                                    Lender username: %s""", rs.getObject("id", UUID.class), rs.getString("title"),
                                    rs.getString("author"), rs.getString("genre"), rs.getString("description"),
                                    rs.getString("lenderUsername")));
                    Communication.sendMessage(writer, "");
                }
            }
            Communication.sendMessage(writer, "Total: " + total);
        }
    }
}
