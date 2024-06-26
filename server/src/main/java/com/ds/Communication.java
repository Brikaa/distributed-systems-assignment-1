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
        String msg = reader.readLine();
        if (msg == null)
            throw new IOException("Received null message");
        return msg;
    }

    public static String receiveNonEmptyMessage(BufferedReader reader, BufferedWriter writer) throws IOException {
        String msg = receiveMessage(reader);
        while (msg.isEmpty()) {
            sendMessage(writer, "Can't be empty");
            msg = receiveMessage(reader);
        }
        return msg;
    }

    public static int receiveMessageInRange(BufferedReader reader, BufferedWriter writer, int start, int end)
            throws IOException {
        Integer response = parseIntOrNull(receiveNonEmptyMessage(reader, writer));
        while (response == null || response < start || response > end) {
            sendMessage(writer, "Invalid choice");
            response = parseIntOrNull(receiveNonEmptyMessage(reader, writer));
        }
        return response;
    }

    private static Integer parseIntOrNull(String str) {
        if (!str.matches("-?\\d+")) {
            return null;
        }
        return Integer.parseInt(str);
    }

    public static double receivePositiveDouble(BufferedReader reader, BufferedWriter writer) throws IOException {
        Double msg = parsePositiveDoubleOrNull(receiveNonEmptyMessage(reader, writer));
        while (msg == null) {
            sendMessage(writer, "Must be a positive real number");
            msg = parsePositiveDoubleOrNull(receiveNonEmptyMessage(reader, writer));
        }
        return msg;
    }

    private static Double parsePositiveDoubleOrNull(String str) {
        if (!str.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        return Double.parseDouble(str);
    }

}
