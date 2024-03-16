import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Map;
import java.util.Scanner;

public class Main {
    // Receive message from the server
    // Show it to the user
    // Wait for the user's input
    // Send the user's input to the server

    public static void main(String[] args) throws NumberFormatException, IOException {
        final Map<String, String> env = System.getenv();
        Socket socket = new Socket(env.get("SERVER_HOST"), Integer.parseInt(env.get("SERVER_PORT")));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Gracefully stopping the client...");
                socket.close();
            } catch (IOException e) {
                System.out.println("Failed to close resources: " + e);
            }
        }));

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                while (true) {
                    try {
                        String msg = reader.readLine();
                        if (msg == null) {
                            System.out.println("Received null message, exiting...");
                            System.exit(1);
                        }
                        System.out.println(msg);
                    } catch (IOException e) {
                        System.out.println("Failed to read from the server, exiting...: " + e);
                        System.exit(1);
                    }
                }
            } catch (IOException e) {
                System.out.println("Failed to close reader: " + e);
            }
        });
        readerThread.start();

        Thread writerThread = new Thread(() -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                    Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    try {
                        String msg = scanner.nextLine();
                        writer.write(msg);
                        writer.newLine();
                        writer.flush();
                    } catch (IOException e) {
                        System.out.println("Failed to write to the server, exiting...: " + e);
                        System.exit(1);
                    }
                }
            } catch (IOException e) {
                System.out.println("Failed to close writers: " + e);
            }
        });
        writerThread.start();
    }
}
