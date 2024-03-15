package com.ds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

public class Communication {
    public static void sendMessage(BufferedWriter writer, String message) throws IOException {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    public static String receiveMessage(BufferedReader reader) throws IOException {
        return reader.readLine();
    }

    public static Integer receiveMessageInRange(BufferedReader reader, BufferedWriter writer, int start, int end)
            throws IOException {
        Integer response = parseIntOrNull(receiveMessage(reader));
        while (response == null) {
            sendMessage(writer, "Invalid choice");
            response = parseIntOrNull(receiveMessage(reader));
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
