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
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        Scanner scanner = new Scanner(System.in);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Gracefully stopping the client...");
                reader.close();
                writer.close();
                socket.close();
                scanner.close();
                System.exit(0);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));

        Thread readerThread = new Thread(() -> {
            while (true) {
                try {
                    System.out.println(reader.readLine());
                } catch (IOException e) {
                    System.out.println("Failed to read from the server: " + e);
                    return;
                }
            }
        });
        readerThread.start();

        while (true) {
            try {
                String msg = scanner.nextLine();
                writer.write(msg);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.out.println("Failed to read from the server: " + e);
            }
        }
    }
}
