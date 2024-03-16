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
                    Communication.sendMessage(
                            writer,
                            """
                                    1. List available books
                                    2. Search for a book
                                    3. View detailed information about book""");
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

    private static void register(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Your name");
        String name = Communication.receiveMessage(reader);
        Communication.sendMessage(writer, "Your username");
        String username = Communication.receiveMessage(reader);
        Communication.sendMessage(writer, "Your password");
        String password = Communication.receiveMessage(reader);
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
        String username = Communication.receiveMessage(reader);

        try (PreparedStatement st = conn.prepareStatement("SELECT id, password FROM AppUser WHERE username=?")) {
            st.setString(1, username);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    Communication.sendMessage(writer, "404. Invalid username");
                    return;
                }

                Communication.sendMessage(writer, "Your password");
                String password = Communication.receiveMessage(reader);
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
        CommonMainLoop.listAvailableBooksByCondition(conn, writer, reader, "", new Binding[] {});
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
        String filter = Communication.receiveMessage(reader);
        CommonMainLoop.listAvailableBooksByCondition(
                conn,
                writer,
                reader,
                String.format("AND %s=?", filterOn),
                new Binding[] { (st) -> st.setString(1, filter) });
    }

    private static void viewBookDetails(Connection conn, BufferedWriter writer, BufferedReader reader)
            throws IOException, SQLException {
        Communication.sendMessage(writer, "Book id");
        String id = Communication.receiveMessage(reader);
        try (PreparedStatement st = conn.prepareStatement(
                CommonMainLoop.buildAvailableBooksQuery("id, title, author, genre, description", "AND id=?"))) {
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
}
