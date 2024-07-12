package ovh.maddie480.mrreport;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
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
        JSONObject messageBody = new JSONObject();
        messageBody.put("channel", channel);
        messageBody.put("username", username);
        messageBody.put("icon_emoji", emoji);
        messageBody.put("text", message);

        callAPI("chat.postMessage", messageBody);
    }

    public static void addReaction(String channel, String emoji, String message) throws IOException {
        addOrRemoveReaction("reactions.add", channel, emoji, message);
    }

    public static void removeReaction(String channel, String emoji, String message) throws IOException {
        addOrRemoveReaction("reactions.remove", channel, emoji, message);
    }

    private static void addOrRemoveReaction(String api, String channel, String emoji, String message) throws IOException {
        JSONObject messageBody = new JSONObject();
        messageBody.put("channel", channel);
        messageBody.put("timestamp", message);
        messageBody.put("name", emoji);

        callAPI(api, messageBody);
    }

    public static JSONArray getLatestMessages(String channel) throws IOException {
        JSONObject messageBody = new JSONObject();
        messageBody.put("channel", channel);

        return callAPI("conversations.history", messageBody).getJSONArray("messages");
    }

    public static JSONArray listReactions(String channel, String message) throws IOException {
        JSONObject result = callAPI("reactions.get?full=true&channel=" + channel + "&timestamp=" + message, new JSONObject()).getJSONObject("message");
        return result.has("reactions") ? result.getJSONArray("reactions") : new JSONArray();
    }

    private static JSONObject callAPI(String api, JSONObject body) throws IOException {
        return ConnectionUtils.runWithRetry(() -> {
            HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://slack.com/api/" + api);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + System.getenv("GITLAB_SLACK_TOKEN"));

            logger.debug("Calling API {} with body: {}", api, body.toString(2));

            try (OutputStream os = connection.getOutputStream()) {
                IOUtils.write(body.toString(), os, StandardCharsets.UTF_8);
            }

            try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
                JSONObject response = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                logger.debug("Response: {}", response.getBoolean("ok"));
                if (!response.getBoolean("ok")) {
                    throw new IOException("Slack responded with ok=false: " + response.toString(2));
                }
                return response;
            }
        });
    }
}