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
import java.util.Map;

public class MergeRequestAutoLabeler {
    private static final Logger logger = LoggerFactory.getLogger(MergeRequestAutoLabeler.class);

    public static void main(String[] args) throws IOException {
        for (Map.Entry<String, Long> project : GitLabUtils.getProjectIds().entrySet()) {
            logger.debug("Listing MRs of project {}...", project.getKey());
            autoLabelOpenMergeRequests(project.getValue().intValue());
        }

        logger.debug("Done!");
    }

    private static void autoLabelOpenMergeRequests(int projectId) throws IOException {
        for (JSONObject mergeRequest : GitLabUtils.paginatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests?state=opened&with_merge_status_recheck=true&page=")) {
            int mergeRequestId = mergeRequest.getInt("iid");

            int approvalCount = getApprovalCount(projectId, mergeRequestId);

            if (approvalCount == -1) {
                logger.debug("MR !{} => no group found!", mergeRequestId);
                continue;
            }

            JSONObject labelRequest = new JSONObject();
            labelRequest.put("id", projectId);
            labelRequest.put("merge_request_iid", mergeRequestId);
            labelRequest.put("add_labels", System.getenv("GITLAB_LABEL_NAME") + "::" + approvalCount);

            ConnectionUtils.runWithRetry(() -> {
                HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequestId);
                connection.setRequestMethod("PUT");
                connection.setRequestProperty("Private-Token", System.getenv("GITLAB_ACCESS_TOKEN"));
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    IOUtils.write(labelRequest.toString(), os, StandardCharsets.UTF_8);
                }

                if (connection.getResponseCode() != 200) {
                    throw new IOException("Non-200 response code from GitLab: " + connection.getResponseCode());
                }

                return null;
            });
        }
    }

    static int getApprovalCount(int projectId, int mergeRequestId) throws IOException {
        JSONObject approvals = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = GitLabUtils.authenticatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequestId + "/approval_state")) {
                return new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            }
        });

        int approvalCount = -1;

        for (Object r : approvals.getJSONArray("rules")) {
            JSONObject rule = (JSONObject) r;

            boolean hasGroup = false;

            for (Object g : rule.getJSONArray("groups")) {
                JSONObject group = (JSONObject) g;
                if (System.getenv("GITLAB_LABEL_GROUP_NAME").equals(group.getString("full_path"))) {
                    hasGroup = true;
                    break;
                }
            }

            if (hasGroup) {
                approvalCount = rule.getJSONArray("approved_by").length();
                logger.debug("MR !{} => {} approval(s)", mergeRequestId, approvalCount);
                break;
            }
        }

        return approvalCount;
    }
}
