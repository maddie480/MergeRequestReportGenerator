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

public final class GitLabUtils {
    private static final Logger logger = LoggerFactory.getLogger(GitLabUtils.class);

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
