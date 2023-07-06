package ovh.maddie480.mrreport;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class GitLabUtils {
    private static final Logger logger = LoggerFactory.getLogger(GitLabUtils.class);

    public static Map<String, Long> getProjectIds() throws IOException {
        Map<String, Long> projectIds = new TreeMap<>();

        for (JSONObject group : GitLabUtils.paginatedRequest("https://gitlab.com/api/v4/groups/" + System.getenv("GITLAB_GROUP_ID") + "/descendant_groups?page=")) {
            long groupId = group.getLong("id");

            logger.debug("Going through projects of group {} ({})...", group.getString("full_name"), groupId);

            for (JSONObject project : GitLabUtils.paginatedRequest("https://gitlab.com/api/v4/groups/" + groupId + "/projects?page=")) {
                if (!project.getString("path").startsWith("test-project-")) {
                    projectIds.put(project.getString("name_with_namespace"), project.getLong("id"));
                }
            }
        }

        logger.debug("Got project IDs: {}", projectIds);
        return projectIds;
    }

    public static InputStream authenticatedRequest(String url) throws IOException {
        logger.debug("Requesting GitLab URL {}", url);

        HttpURLConnection connAuth = ConnectionUtils.openConnectionWithTimeout(url);
        connAuth.setRequestProperty("Private-Token", System.getenv("GITLAB_ACCESS_TOKEN"));

        return ConnectionUtils.connectionToInputStream(connAuth);
    }

    public static List<JSONObject> paginatedRequest(String url) throws IOException {
        List<JSONObject> result = new ArrayList<>();
        int page = 1;

        while (true) {
            final int curPage = page;

            JSONArray elements = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = authenticatedRequest(url + curPage)) {
                    return new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
                }
            });

            if (elements.isEmpty()) {
                return result;
            }

            for (int i = 0; i < elements.length(); i++) {
                result.add(elements.getJSONObject(i));
            }

            page++;
        }
    }
}
