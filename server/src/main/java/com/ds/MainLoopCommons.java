package com.ds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                        WHERE BookBorrowRequest.status != 'BORROWED' AND Book.lenderId != ? """ + extraConditions)) {
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

    private static void sendBorrowRequest(Connection conn, UUID sessionId, UUID bookId)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO BookBorrowRequest(bookId, borrowerId, status) VALUES (?, ?, 'PENDING')")) {
            MainLoopCommons.applyBindings(st, List.of(
                    (i, s) -> s.setObject(i, bookId),
                    (i, s) -> s.setObject(i, sessionId)));
            st.executeUpdate();
        }
    }

    public static void listBorrowRequestsByCondition(Connection conn, BufferedWriter writer, BufferedReader reader,
            String condition, List<Binding> bindings)
            throws IOException, SQLException {
        // id - from: xxxx - A Tale of Two Cities
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT
                    id,
                    status,
                    Borrower.username AS borrowerUsername,
                    Lender.username AS lenderUsername,
                    Book.name as bookName
                FROM BookBorrowRequest
                LEFT JOIN Book ON Book.id = BookBorrowRequest.bookId
                LEFT JOIN AppUser AS Borrower ON Borrower.id = BookBorrowRequest.borrowerId
                LEFT JOIN AppUser AS Lender ON Lender.id = Book.lenderId
                WHERE """ + condition)) {
            applyBindings(st, bindings);

            ArrayList<UUID> requestIds = new ArrayList<>();
            int i = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    requestIds.add(rs.getObject("id", UUID.class));
                    Communication.sendMessage(
                            writer,
                            String.format("%s. requester: %s - lender: %s - %s (%s)",
                                    ++i,
                                    rs.getString("borrowerUsername"),
                                    rs.getString("lenderUsername"),
                                    rs.getString("bookName"),
                                    rs.getString("status")));
                }
            }
            if (i == 0)
                return;
            Communication.sendMessage(writer, "1. Accept request\n2. Reject request\n3. Back");
            int choice = Communication.receiveMessageInRange(reader, writer, 1, 3);
            if (choice != 3) {
                Communication.sendMessage(writer, "Enter the request number");
                int requestIndex = Communication.receiveMessageInRange(reader, writer, 1, i) - 1;
                updateBorrowRequestStatus(conn, requestIds.get(requestIndex), choice == 1 ? "BORROWED" : "REJECTED");
            }
        }
    }

    private static void updateBorrowRequestStatus(Connection conn, UUID requestId, String status)
            throws IOException, SQLException {
        try (PreparedStatement st = conn.prepareStatement("UPDATE BookBorrowRequest SET status = ? WHERE id = ?")) {
            MainLoopCommons.applyBindings(st, List.of(
                    (i, s) -> s.setString(i, status),
                    (i, s) -> s.setObject(i, requestId)));
            st.executeUpdate();
        }
    }
}
