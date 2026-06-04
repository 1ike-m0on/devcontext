import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class DevContextHttpClient {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "REVIEW".equalsIgnoreCase(args[0])) {
            sendReview(args);
            return;
        }

        if (args.length < 3 || args.length > 5) {
            printUsage();
            System.exit(2);
        }

        String method = args[0].trim().toUpperCase();
        String url = args[1];
        int timeoutSeconds = Integer.parseInt(args[2]);
        String body = args.length >= 4 ? Files.readString(Path.of(args[3]), StandardCharsets.UTF_8) : null;
        String outputPath = args.length == 5 ? args[4] : null;

        send(method, url, timeoutSeconds, body, outputPath);
    }

    private static void sendReview(String[] args) throws Exception {
        if (args.length != 7 && args.length != 8) {
            printUsage();
            System.exit(2);
        }

        String url = args[1];
        int timeoutSeconds = Integer.parseInt(args[2]);
        String baseBranch = args[3];
        String compareBranch = args[4];
        String mode = args[5];
        String diffText = Files.readString(Path.of(args[6]), StandardCharsets.UTF_8);
        String outputPath = args.length == 8 ? args[7] : null;

        String body = "{"
                + "\"baseBranch\":\"" + jsonEscape(baseBranch) + "\","
                + "\"compareBranch\":\"" + jsonEscape(compareBranch) + "\","
                + "\"mode\":\"" + jsonEscape(mode) + "\","
                + "\"diffText\":\"" + jsonEscape(diffText) + "\""
                + "}";

        send("POST", url, timeoutSeconds, body, outputPath);
    }

    private static void send(String method, String url, int timeoutSeconds, String body, String outputPath) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json");

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            System.err.println("HTTP " + response.statusCode() + ": " + response.body());
            System.exit(1);
        }
        if (outputPath == null || outputPath.isBlank()) {
            System.out.print(response.body());
            return;
        }
        Files.writeString(Path.of(outputPath), response.body(), StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java DevContextHttpClient.java METHOD URL TIMEOUT_SECONDS [BODY_FILE] [OUTPUT_FILE]");
        System.err.println("  java DevContextHttpClient.java REVIEW URL TIMEOUT_SECONDS BASE_BRANCH COMPARE_BRANCH MODE DIFF_FILE [OUTPUT_FILE]");
    }
}
