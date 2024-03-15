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
        Map<String, String> env = System.getenv();

        // Set up db connection
        Properties props = new Properties();
        props.setProperty("user", env.get("DB_USER"));
        props.setProperty("password", env.get("DB_PASSWORD"));
        String url = String.format("jdbc:postgresql://%s/%s", env.get("DB_HOST"), env.get("DB_NAME"));
        Connection conn = DriverManager.getConnection(url, props);

        // Set up socket connection
        ServerSocket socket = new ServerSocket(Integer.parseInt(env.get("PORT")));

        // Set up graceful exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Exiting the server...");
                socket.close();
            } catch (IOException e) {
                System.out.println("Failed to close the socket");
            }
        }));

        // Connection accepting loop
        System.out.println("Listening for connections...");
        while (true) {
            Socket client = socket.accept();
            System.out.println("Accepted client connection");

            // Per-client thread
            Thread t = new Thread(() -> {
                BufferedWriter writer;
                BufferedReader reader;
                try {
                    writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
                    reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    runClientLoop(conn, writer, reader);
                } catch (IOException e) {
                    System.out.println("Failed to communicate with client: " + e.toString());
                    return;
                }
            });

            t.start();
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
        Database.withPreparedStatement(
                conn,
                "SELECT id FROM AppUser WHERE username=?",
                new ParameterSetting[] { (i, st) -> st.setString(i, username) },
                (st) -> {
                    ResultSet rs = st.executeQuery();
                    if (rs.next()) {
                        Communication.sendMessage(writer, "400. A user with this username already exists");
                        return;
                    }
                    rs.close();
                });

        // Insert user
        Database.withPreparedStatement(
                conn,
                "INSERT INTO AppUser(username, name, password, isAdmin) VALUES (?, ?, ?, ?)",
                new ParameterSetting[] {
                        (i, st) -> st.setString(i, username),
                        (i, st) -> st.setString(i, name),
                        (i, st) -> st.setString(i, password),
                        (i, st) -> st.setBoolean(i, isAdmin)
                },
                (st) -> st.executeUpdate());
    }
}
