package com.ds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

interface Binding {
    void apply(PreparedStatement st) throws SQLException;
}

public class CommonMainLoop {
    public static void listAvailableBooksByCondition(Connection conn, BufferedWriter writer, BufferedReader reader,
            String extraConditions, Binding[] bindings) throws IOException, SQLException {
        try (PreparedStatement st = conn
                .prepareStatement("""
                        SELECT id, title, author, genre
                        FROM Book
                        LEFT JOIN BookBorrowRequest ON Book.id = BookBorrowRequest.bookId
                        WHERE BookBorrowRequest.status != 'BORROWED' """ + extraConditions)) {
            for (Binding binding : bindings) {
                binding.apply(st);
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Communication.sendMessage(writer,
                            String.format("%s - %s - By %s - %s", rs.getObject("id", UUID.class), rs.getString("title"),
                                    rs.getString("author"), rs.getString("genre")));
                }
            }
        }

    }
}
