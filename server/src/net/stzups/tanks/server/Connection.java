package net.stzups.tanks.server;

import net.stzups.tanks.Tanks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Queue;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Connection implements Runnable {

    private static final Logger logger = Logger.getLogger(Tanks.class.getName());

    private Server server;
    private UUID uuid;
    private Socket socket;
    private long lastPing = System.currentTimeMillis();
    private int ping = 0;
    private Queue<byte[]> queue = Collections.asLifoQueue(new ArrayDeque<>());
    private boolean connected = true;

    Connection(Server server, Socket socket) {
        this.server = server;
        this.socket = socket;
        this.uuid = UUID.randomUUID();

        InetAddress inetAddress = this.socket.getInetAddress();
        if (server.getClientsMap().containsKey(inetAddress))
            server.getClientsMap().get(inetAddress).close();
        server.getClientsMap().put(this.socket.getInetAddress(), this);

        new Thread(this).start();
    }

    public UUID getUUID() {
        return uuid;
    }

    public Socket getSocket() {
        return socket;
    }

    public void run() {
        Thread manager = new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && connected) {
                try {
                    Thread.sleep(1000);
                    if (ping == -1) {
                        close();
                    }
                    lastPing = System.currentTimeMillis();
                    sendPacket((byte) 0x9, "");
                } catch (InterruptedException e) {
                    close();
                }
            }
        });
        manager.start();

        try (InputStream inputStream = socket.getInputStream();
             OutputStream outputStream = socket.getOutputStream()){

            try (Scanner scanner = new Scanner(inputStream, "UTF-8")){
                String data = scanner.useDelimiter("\\r\\n\\r\\n").next();
                Matcher get = Pattern.compile("^GET").matcher(data);

                if (get.find()) {
                    Matcher webSocket = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);

                    if (webSocket.find()) {
                        byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
                                + "Connection: Upgrade\r\n"
                                + "Upgrade: websocket\r\n"
                                + "Sec-WebSocket-Accept: "
                                + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((webSocket.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)))
                                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        outputStream.write(response, 0, response.length);

                        logger.info("Client connected from IP address " + socket.getInetAddress().getHostAddress());
                        connection:
                        while(connected) {
                            if (inputStream.available() > 0) {
                                byte[] head = new byte[2];
                                inputStream.read(head, 0, 2);
                                if (((head[0] >> 7) & 1 ) != 1) { // FIN bit todo handle non FIN
                                    logger.info("not FIN");
                                }
                                byte[] decoded;
                                if (((head[1] >> 7) & 1) == 1) { // Mask bit
                                    switch (head[1] + 0x80) {
                                        case 126:
                                            inputStream.skip(2); //todo read these?
                                            break;
                                        case 127:
                                            inputStream.skip(4);
                                            break;
                                    }
                                    byte[] key = new byte[4];
                                    inputStream.read(key, 0, 4);
                                    byte[] encoded = new byte[inputStream.available()];
                                    decoded = new byte[encoded.length];
                                    inputStream.read(encoded);
                                    for (int i = 0; i < encoded.length; i++) {
                                        decoded[i] = (byte) (encoded[i] ^ key[i & 0x3]);
                                    }
                                } else {
                                    byte[] packet = new byte[inputStream.available()];
                                    inputStream.read(packet);
                                    throw new RuntimeException("Received unmasked data from client " + uuid + ", full packet: " + readBytesToString(head) + " " + readBytesToString(packet));
                                }

                                switch (head[0] & 0x0F) { // Opcode bits
                                    case 0x0: // continuation frame
                                        logger.info("continuation frame");
                                        break;
                                    case 0x1: // text frame
                                        server.onTextPacket(this, new String(decoded));
                                        break;
                                    case 0x2: // binary frame
                                        logger.info("binary frame");
                                        break;
                                    case 0x8: // connection close
                                        logger.info("Client disconnected from IP address " + socket.getInetAddress().getHostAddress());
                                        close(true);
                                        break connection;
                                    case 0x9: // ping, shouldn't ever receive one
                                        logger.info("ping");
                                        break;
                                    case 0xA: // pong from client
                                        long time = System.currentTimeMillis();
                                        ping = (int) (time - lastPing);
                                        lastPing = -1;
                                        sendText("ping:" + ping);
                                        break;
                                    default: // error
                                        byte[] packet = new byte[inputStream.available()];
                                        inputStream.read(packet);
                                        throw new RuntimeException("Unrecognized opcode ( "+ readBytesToString((byte) (head[0] & 0x0F)).substring(4) + " ) from client " + getUUID() + ", full packet: " + readBytesToString(head) + " " + readBytesToString(packet));
                                }
                            }

                            if (connected) {
                                while (queue.peek() != null) {
                                    outputStream.write(queue.poll());
                                }
                            }
                        }

                    } else {
                        Matcher path = Pattern.compile("(?<=GET /)\\S+").matcher(data);
                        String foundPath;

                        if (path.find()) {
                            foundPath = path.group();
                        } else {
                            foundPath = "index.html";
                        }


                        File file = new File("resources/client/" + foundPath);

                        if(file.exists()) {
                            outputStream.write(("HTTP/1.1 200 OK\r\n"
                                    + "Server: Tanks\r\n"
                                    + "Date: " + new Date() + "\r\n"
                                    + "Content-type: " + Files.probeContentType(Paths.get(file.getCanonicalPath())) + "\r\n"
                                    + "Content-length: " + file.length() + "\r\n"
                                    + "\r\n").getBytes(StandardCharsets.UTF_8));
                            Files.copy(file.toPath(), outputStream);
                        } else {
                            outputStream.write(("HTTP/1.1 404 Not Found").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } else {
                    outputStream.write(("HTTP/1.1 400 Bad Request").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        if (connected) {
            close(true);
        }
    }

    static private byte[] getFramedPacket(byte opcode, String payload) {
        byte[] data;
        int offset;

        if (payload.length() <= 125) {
             offset = 2;
             data = new byte[offset + payload.length()];
             data[1] = (byte) (payload.length());
        } else if (payload.length() > 126 && payload.length() < 65535) {
            offset = 4;
            data = new byte[offset + payload.length()];
            data[1] = 126;
        } else {
            offset = 6;
            data = new byte[offset + payload.length()];
        }

        data[0] = (byte) (0x80 ^ opcode);

        for (int i = 0; i < payload.length(); i++) {
            data[offset + i] = (byte) payload.charAt(i);
        }

        return data;
    }

    static private String readBytesToString(byte[] bytes) {
        StringBuilder string = new StringBuilder();

        for (byte b : bytes) {
            string.append((b >> 7) & 1);
            string.append((b >> 6) & 1);
            string.append((b >> 5) & 1);
            string.append((b >> 4) & 1);
            string.append((b >> 3) & 1);
            string.append((b >> 2) & 1);
            string.append((b >> 1) & 1);
            string.append(b & 1);
            string.append(" ");
            string.append(" (");
            string.append(b);
            string.append(") ");
        }

        if (string.length() > 0) {
            return string.substring(0, string.length() - 1);
        } else {
            return string.toString();
        }
    }

    static private String readBytesToString(byte b) {
        return readBytesToString(new byte[]{b});
    }

    void sendPacket(byte opcode, String payload) {
        queue.add(getFramedPacket(opcode, payload));
    }

    void sendText(String payload) {
        sendPacket((byte) 0x1, payload);
    }

    boolean isConnected() {
        return connected;
    }

    public void close() {
        close(false);
    }

    public void close(boolean quiet) {
        sendPacket((byte) 0x8, "");
        connected = false;
        if (!quiet)
            logger.info("Closed connection for client from " + socket.getInetAddress().getHostAddress());
        server.getClientsMap().remove(socket.getInetAddress());
    }
}