import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class Main {
    private static String directory;

    public static void main(String[] args) {
        // Parse command-line arguments
        if (args.length > 1 && args[0].equals("--directory")) {
            directory = args[1];
        }
        System.out.println("Logs from your program will appear here!");
        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            while (true) {
                Socket clientSocket = serverSocket.accept(); // Wait for client connection
                System.out.println("Accepted new connection");
                // Handle client connection in a separate thread
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader inputStream = new BufferedReader(
                 new InputStreamReader(clientSocket.getInputStream()));
             OutputStream outputStream = clientSocket.getOutputStream()) {

            // Read the request line
            String requestLine = inputStream.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 3) return;

            String httpMethod = requestParts[0];
            String urlPath = requestParts[1];

            // Read all headers
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while (!(headerLine = inputStream.readLine()).isEmpty()) {
                String[] headerParts = headerLine.split(": ", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0], headerParts[1]);
                }
            }

            // Generate HTTP response
            String httpResponse = getHttpResponse(httpMethod, urlPath, headers, inputStream, outputStream);
            System.out.println("Sending response: " + httpResponse);

            // Send response
            outputStream.write(httpResponse.getBytes("UTF-8"));
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        } finally {
            // Close client socket
            try {
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.out.println("IOException: " + e.getMessage());
            }
        }
    }

    private static String getHttpResponse(String httpMethod, String urlPath,
                                          Map<String, String> headers,
                                          BufferedReader inputStream,
                                          OutputStream outputStream) throws IOException {
        String httpResponse;

        if ("GET".equals(httpMethod)) {
            if ("/".equals(urlPath)) {
                httpResponse = "HTTP/1.1 200 OK\r\n\r\n";
            } else if (urlPath.startsWith("/echo/")) {
                String echoStr = urlPath.substring(6); // Extract text after "/echo/"
                String acceptEncoding = headers.get("Accept-Encoding");
                boolean supportsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");

                if (supportsGzip) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
                        gzipOutputStream.write(echoStr.getBytes("UTF-8"));
                    }
                    byte[] gzipData = byteArrayOutputStream.toByteArray();

                    httpResponse = "HTTP/1.1 200 OK\r\n"
                            + "Content-Encoding: gzip\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: " + gzipData.length + "\r\n\r\n";
                    outputStream.write(httpResponse.getBytes("UTF-8"));
                    outputStream.write(gzipData);
                    return ""; // Gzip response already sent
                } else {
                    httpResponse = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: " + echoStr.length() + "\r\n\r\n"
                            + echoStr;
                }
            } else if ("/user-agent".equals(urlPath)) {
                String userAgent = headers.getOrDefault("User-Agent", "Unknown");
                httpResponse = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + userAgent.length() + "\r\n\r\n"
                        + userAgent;
            } else if (urlPath.startsWith("/files/")) {
                String filename = urlPath.substring(7); // Extract filename
                File file = new File(directory, filename);
                if (file.exists()) {
                    byte[] fileContent = Files.readAllBytes(file.toPath());
                    httpResponse = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/octet-stream\r\n"
                            + "Content-Length: " + fileContent.length + "\r\n\r\n"
                            + new String(fileContent);
                } else {
                    httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
                }
            } else {
                httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
            }
        } else if ("POST".equals(httpMethod) && urlPath.startsWith("/files/")) {
            String filename = urlPath.substring(7); // Extract filename
            File file = new File(directory, filename);

            if (!file.getCanonicalPath().startsWith(new File(directory).getCanonicalPath())) {
                httpResponse = "HTTP/1.1 403 Forbidden\r\n\r\n";
            } else {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                char[] buffer = new char[contentLength];
                int bytesRead = inputStream.read(buffer, 0, contentLength);
                if (bytesRead == contentLength) {
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                        writer.write(buffer, 0, bytesRead);
                    }
                    httpResponse = "HTTP/1.1 201 Created\r\n\r\n";
                } else {
                    httpResponse = "HTTP/1.1 500 Internal Server Error\r\n\r\n";
                }
            }
        } else {
            httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
        }

        return httpResponse;
    }
}
