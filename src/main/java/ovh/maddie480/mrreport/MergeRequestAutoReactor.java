package ovh.maddie480.mrreport;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MergeRequestAutoReactor {
    private static final Logger logger = LoggerFactory.getLogger(MergeRequestAutoLabeler.class);

    public static void main(String[] args) throws IOException {
        Map<String, Long> projectIds = GitLabUtils.getProjectIds();

        Pattern mrRegex = Pattern.compile(".*https://gitlab\\.com/([a-z/-]+)/-/merge_requests/([0-9]+).*", Pattern.DOTALL);
        String channelId = System.getenv("SLACK_REACT_CHANNEL_ID");

        for (Object o : SlackCommunicator.getLatestMessages(channelId)) {
            JSONObject message = (JSONObject) o;
            if (!message.has("text")) continue;

            Matcher matcher = mrRegex.matcher(message.getString("text"));
            if (!matcher.matches()) continue;

            String projectName = matcher.group(1);

            int projectId = projectIds.get(projectName).intValue();
            int mrId = Integer.parseInt(matcher.group(2));

            String mrState = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = GitLabUtils.authenticatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mrId)) {
                    JSONObject mr = new JSONObject(new JSONTokener(is));
                    return mr.getString("state");
                }
            });

            String wantedReaction = switch (mrState) {
                case "closed" -> "x";
                case "locked" -> "lock";
                case "merged" -> "merged";
                case "opened" -> {
                    int approvalCount = MergeRequestAutoLabeler.getApprovalCount(projectId, mrId);
                    if (approvalCount == -1) yield null;
                    yield new String[]{"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"}[Math.min(10, approvalCount)];
                }
                default -> "question";
            };

            logger.debug("Wanted reaction for {} MR !{}: {}", projectName, mrId, wantedReaction);

            String messageId = message.getString("ts");

            boolean alreadyReacted = false;

            for (Object o2 : SlackCommunicator.listReactions(channelId, messageId)) {
                JSONObject reaction = (JSONObject) o2;

                boolean hasUser = false;
                for (Object o3 : reaction.getJSONArray("users")) {
                    String userId = (String) o3;
                    if (System.getenv("SLACK_SELF_USER").equals(userId)) {
                        hasUser = true;
                        break;
                    }
                }
                if (!hasUser) continue;

                if (reaction.getString("name").equals(wantedReaction)) {
                    alreadyReacted = true;
                } else {
                    logger.info("Removing reaction {} on message {}", reaction.getString("name"), messageId);
                    SlackCommunicator.removeReaction(channelId, reaction.getString("name"), messageId);
                }
            }

            if (!alreadyReacted && wantedReaction != null) {
                logger.info("Adding reaction {} on message {}", wantedReaction, messageId);
                SlackCommunicator.addReaction(channelId, wantedReaction, messageId);
            }
        }
    }
}
