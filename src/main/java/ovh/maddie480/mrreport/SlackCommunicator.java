package ovh.maddie480.mrreport;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public final class SlackCommunicator {
    private static final Logger logger = LoggerFactory.getLogger(SlackCommunicator.class);

    public static void sendMessage(String channel, String username, String emoji, String message) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://slack.com/api/chat.postMessage");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + System.getenv("GITLAB_SLACK_TOKEN"));

            JSONObject messageBody = new JSONObject();
            messageBody.put("channel", channel);
            messageBody.put("username", username);
            messageBody.put("icon_emoji", emoji);
            messageBody.put("text", message);

            logger.debug("Sending message: {}", messageBody.toString(2));

            try (OutputStream os = connection.getOutputStream()) {
                IOUtils.write(messageBody.toString(), os, StandardCharsets.UTF_8);
            }

            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                logger.debug("Response: {}", response.getBoolean("ok"));
                if (!response.getBoolean("ok")) {
                    throw new IOException("Slack responded with ok=false: " + response.toString(2));
                }
                return response.getString("ts");
            }
        });
    }
}