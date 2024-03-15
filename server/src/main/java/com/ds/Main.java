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
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

class Session {
    public String id;
}

public class Main {
    public static void main(String[] args) throws SQLException, NumberFormatException, IOException {
        Map<String, String> env = System.getenv();
        Properties props = new Properties();
        props.setProperty("user", env.get("DB_USER"));
        props.setProperty("password", env.get("DB_PASSWORD"));
        String url = String.format("jdbc:postgresql://%s/%s", env.get("DB_HOST"), env.get("DB_NAME"));
        Connection conn = DriverManager.getConnection(url, props);
        ServerSocket socket = new ServerSocket(Integer.parseInt(System.getenv("PORT")));

        // Connection accepting loop
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
                    runClientLoop(writer, reader);
                } catch (IOException e) {
                    System.out.println("Failed to communicate with client: " + e.toString());
                    return;
                }
            });

            t.start();
        }
    }

    private static void runClientLoop(BufferedWriter writer, BufferedReader reader) throws IOException {
        Session session = new Session();
        while (true) {
            if (session.id == null) {
                sendMessage(writer, "1. Register\n 2. Login");
                Integer choice = getInputInRange(reader, writer, 1, 2);
            }
        }
    }

    private static void sendMessage(BufferedWriter writer, String message) throws IOException {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    private static Integer getInputInRange(BufferedReader reader, BufferedWriter writer, int start, int end)
            throws IOException {
        Integer response = parseIntOrNull(reader.readLine());
        while (response == null) {
            sendMessage(writer, "Invalid choice");
            response = parseIntOrNull(reader.readLine());
        }
        return response;
    }

    private static Integer parseIntOrNull(String str) {
        if (!str.matches("-?\\d+")) {
            return null;
        }
        return Integer.parseInt(str);
    }

}
