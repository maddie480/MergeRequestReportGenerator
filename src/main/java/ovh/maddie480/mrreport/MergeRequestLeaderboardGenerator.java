package ovh.maddie480.mrreport;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MergeRequestLeaderboardGenerator {
    private static final Logger logger = LoggerFactory.getLogger(MergeRequestLeaderboardGenerator.class);

    public static void main(String[] args) throws IOException {
        Map<String, Integer> mergeRequests = new HashMap<>();
        Map<String, Integer> comments = new HashMap<>();
        Map<String, Set<Integer>> approvals = new HashMap<>();
        Map<String, Integer> scores = new HashMap<>();

        for (long projectId : GitLabUtils.getProjectIds().values()) {
            Map<Integer, String> authorCache = new HashMap<>();

            logger.debug("Listing events of project {}...", projectId);

            for (JSONObject event : GitLabUtils.paginatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/events?after=" +
                    ZonedDateTime.now().withDayOfMonth(1).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE) + "&page=")) {

                String author = event.getJSONObject("author").getString("name");

                if (event.isNull("target_type")
                        || !Arrays.asList("DiffNote", "Note", "MergeRequest").contains(event.getString("target_type")))
                    continue;

                logger.debug("{} {} {} {} at {}", author, event.getString("action_name"), event.getString("target_type"),
                        event.getInt("target_iid"), event.getString("created_at"));

                switch (event.getString("action_name")) {
                    case "opened" -> {
                        mergeRequests.put(author, mergeRequests.getOrDefault(author, 0) + 1);
                        scores.put(author, scores.getOrDefault(author, 0) + 1);
                    }

                    case "commented on" -> {
                        if (!"MergeRequest".equals(event.getJSONObject("note").getString("noteable_type"))) {
                            break;
                        }

                        // whose merge request is it?
                        String mrAuthor = ConnectionUtils.runWithRetry(() -> {
                            int mrId = event.getJSONObject("note").getInt("noteable_iid");
                            if (authorCache.containsKey(mrId)) {
                                return authorCache.get(mrId);
                            }

                            logger.debug("Checking author of MR !{}...", mrId);
                            try (InputStream is = GitLabUtils.authenticatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mrId)) {
                                JSONObject mr = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                                String authorName = mr.getJSONObject("author").getString("username");
                                authorCache.put(mrId, authorName);
                                return authorName;
                            }
                        });

                        // only award points for comments left on others' MRs
                        if (!mrAuthor.equals(event.getJSONObject("author").getString("username"))) {
                            comments.put(author, comments.getOrDefault(author, 0) + 1);
                            scores.put(author, scores.getOrDefault(author, 0) + 2);
                        }
                    }

                    case "approved" -> {
                        int mrId = event.getInt("target_id");
                        Set<Integer> alreadyApproved = approvals.getOrDefault(author, new HashSet<>());

                        if (!alreadyApproved.contains(mrId)) {
                            alreadyApproved.add(mrId);
                            approvals.put(author, alreadyApproved);

                            scores.put(author, scores.getOrDefault(author, 0) + 1);
                        }
                    }
                }
            }

            logger.debug("Scores so far: {}", scores);
        }

        List<String> rankings = new ArrayList<>(scores.keySet());
        rankings.sort(Comparator.<String>comparingInt(scores::get).reversed().thenComparing(nick -> nick));

        StringBuilder message = new StringBuilder("*Classement GitLab du mois de ")
                .append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH)))
                .append("*\n");

        List<String> excludedNicknames = Arrays.stream(System.getenv("GITLAB_EXCLUDED_NICKNAMES").split(",")).toList();

        int rank = 1;
        for (String nick : rankings) {
            if (excludedNicknames.contains(nick)) continue;

            message
                    .append('*').append(rank).append(rank == 1 ? "er" : "ème").append("* - *")
                    .append(nick).append("* avec ")
                    .append(scores.get(nick)).append(scores.get(nick) == 1 ? " pt " : " pts ").append('(');

            boolean first = true;
            if (mergeRequests.containsKey(nick)) {
                message.append(mergeRequests.get(nick)).append(mergeRequests.get(nick) == 1 ? " MR ouverte" : " MR ouvertes");
                first = false;
            }
            if (comments.containsKey(nick)) {
                if (!first) message.append(", ");
                message.append(comments.get(nick)).append(comments.get(nick) == 1 ? " commentaire" : " commentaires");
                first = false;
            }
            if (approvals.containsKey(nick)) {
                if (!first) message.append(", ");
                message.append(approvals.get(nick).size()).append(approvals.get(nick).size() == 1 ? " approbation" : " approbations");
            }

            message.append(")\n");
            rank++;
        }

        String messageFinal = message.toString().trim();

        SlackCommunicator.sendMessage(System.getenv("GITLAB_SLACK_CHANNEL"), "GitLab MR Leaderboard", "trophy", messageFinal);
    }
}
