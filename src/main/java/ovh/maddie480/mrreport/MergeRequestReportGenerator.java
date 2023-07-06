package ovh.maddie480.mrreport;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class MergeRequestReportGenerator {
    private static final Logger logger = LoggerFactory.getLogger(MergeRequestReportGenerator.class);

    public static void main(String[] args) throws IOException {
        Map<String, List<MergeRequest>> mergeRequestsPerProject = new TreeMap<>();
        Map<String, Map<String, List<MergeRequest>>> mergeRequestsPerUserPerProject = new HashMap<>();
        Map<String, String> usernamesToNames = new HashMap<>();

        for (Map.Entry<String, Long> project : getProjectIds().entrySet()) {
            logger.debug("Listing MRs of project {}...", project.getKey());
            MergeRequestReportResult result = mrReport(project.getValue().intValue());

            if (!result.retrievedMergeRequests().isEmpty()) {
                mergeRequestsPerProject.put(project.getKey(), result.retrievedMergeRequests());
            }
            for (Map.Entry<String, List<MergeRequest>> resultsPerUser : result.retrievedMergeRequestsPerMember().entrySet()) {
                Map<String, List<MergeRequest>> userMRs = mergeRequestsPerUserPerProject.getOrDefault(resultsPerUser.getKey(), new TreeMap<>());
                userMRs.put(project.getKey(), resultsPerUser.getValue());
                mergeRequestsPerUserPerProject.put(resultsPerUser.getKey(), userMRs);
            }

            usernamesToNames.putAll(result.usernamesToNames());
        }

        Path dir = Paths.get("/tmp/mr_report_generator");
        Files.createDirectory(dir);

        HtmlReportGenerator.generate("Merge requests on all projects", mergeRequestsPerProject, usernamesToNames, dir.resolve("all.html"));

        for (Map.Entry<String, Map<String, List<MergeRequest>>> userProject : mergeRequestsPerUserPerProject.entrySet()) {
            HtmlReportGenerator.generate("Merge requests for " + usernamesToNames.get(userProject.getKey()),
                    userProject.getValue(), usernamesToNames, dir.resolve(userProject.getKey() + ".html"));
        }

        logger.debug("Done!");
    }

    private static Map<String, Long> getProjectIds() throws IOException {
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

    private record MergeRequestReportResult(List<MergeRequest> retrievedMergeRequests,
                                            Map<String, List<MergeRequest>> retrievedMergeRequestsPerMember,
                                            Map<String, String> usernamesToNames) {
    }

    private static MergeRequestReportResult mrReport(int projectId) throws IOException {
        List<MergeRequest> retrievedMergeRequests = new ArrayList<>();
        Map<String, List<MergeRequest>> retrievedMergeRequestsPerMember = new HashMap<>();
        Map<String, String> usernamesToNames = new HashMap<>();

        for (JSONObject mergeRequest : GitLabUtils.paginatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests?state=opened&with_merge_status_recheck=true&page=")) {
            if (mergeRequest.getBoolean("draft")) {
                continue;
            }

            // parse labels
            List<String> labels = new ArrayList<>();
            for (Object l : mergeRequest.getJSONArray("labels")) {
                labels.add((String) l);
            }

            // list people that still need to approve
            ApproverList approvers = ConnectionUtils.runWithRetry(() -> {
                JSONObject approvals;
                try (InputStream is = GitLabUtils.authenticatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequest.getInt("iid") + "/approval_state")) {
                    approvals = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                }

                List<ApproverList> approverCategoryList = new ArrayList<>();
                Set<String> allUsers = new HashSet<>();

                for (Object r : approvals.getJSONArray("rules")) {
                    JSONObject rule = (JSONObject) r;

                    if (rule.getBoolean("approved")) {
                        continue;
                    }

                    int approvalsRequired = rule.getInt("approvals_required") - rule.getJSONArray("approved_by").length();
                    Set<String> eligibleApprovers = new HashSet<>();
                    for (Object a : rule.getJSONArray("eligible_approvers")) {
                        eligibleApprovers.add(((JSONObject) a).getString("username"));

                        // take this opportunity to save username -> name association
                        usernamesToNames.put(((JSONObject) a).getString("username"), ((JSONObject) a).getString("name"));
                    }
                    for (Object a : rule.getJSONArray("approved_by")) {
                        eligibleApprovers.remove(((JSONObject) a).getString("username"));
                    }
                    if (eligibleApprovers.isEmpty()) continue;

                    approverCategoryList.add(new ApproverList(approvalsRequired, eligibleApprovers));
                    allUsers.addAll(eligibleApprovers);
                }

                return findSmallestSetOfUsers(approverCategoryList, new ArrayList<>(allUsers), 1);
            });

            String pipelineStatus = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = GitLabUtils.authenticatedRequest("https://gitlab.com/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequest.getInt("iid"))) {
                    JSONObject mr = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
                    if (mr.has("head_pipeline") && !mr.isNull("head_pipeline")) {
                        return mr.getJSONObject("head_pipeline").getString("status");
                    }
                    return "unknown";
                }
            });

            logger.debug("=> MR {}, with labels {}, approvals required from {}, status {} and pipeline {}", mergeRequest.getString("reference"),
                    labels, approvers, mergeRequest.getString("merge_status"), pipelineStatus);

            // - pipeline failed and conflict warning
            if (!Arrays.asList("unknown", "success").contains(pipelineStatus)) {
                labels.add("pipeline " + pipelineStatus);
            }
            if ("cannot_be_merged".equals(mergeRequest.getString("merge_status"))) {
                labels.add("conflict");
            }

            MergeRequest mr = new MergeRequest(
                    mergeRequest.getString("reference"), mergeRequest.getString("web_url"),
                    mergeRequest.getString("title"), mergeRequest.getJSONObject("author").getString("name"),
                    labels, approvers);

            retrievedMergeRequests.add(mr);

            Set<String> memberUsernames = new HashSet<>();
            memberUsernames.add(mergeRequest.getJSONObject("author").getString("username"));
            memberUsernames.addAll(approvers.possibleApprovers());

            for (String member : memberUsernames) {
                String[] reviewLabels = System.getenv("GITLAB_NEEDS_REVIEW_LABELS").split(",");

                boolean needsCodeReview = Arrays.stream(reviewLabels).anyMatch(labels::contains);
                boolean canApproveMR = approvers.possibleApprovers().contains(member) && needsCodeReview;

                boolean shouldFixMR = member.equals(mergeRequest.getJSONObject("author").getString("username"))
                        && (!needsCodeReview
                        || "cannot_be_merged".equals(mergeRequest.getString("merge_status"))
                        || !Arrays.asList("unknown", "success").contains(pipelineStatus));

                if (canApproveMR || shouldFixMR) {
                    List<MergeRequest> userMergeRequests = retrievedMergeRequestsPerMember.getOrDefault(member, new ArrayList<>());
                    userMergeRequests.add(mr);
                    retrievedMergeRequestsPerMember.put(member, userMergeRequests);
                }
            }
        }

        return new MergeRequestReportResult(retrievedMergeRequests, retrievedMergeRequestsPerMember, usernamesToNames);
    }

    private static ApproverList findSmallestSetOfUsers(List<ApproverList> approvalsNeeded, List<String> users, int minUserCount) {
        if (minUserCount > users.size()) {
            return new ApproverList(0, Collections.emptySet());
        }

        Set<String> winners = new HashSet<>();

        int[] indices = new int[minUserCount];
        Set<Set<String>> alreadyProcessed = new HashSet<>();

        while (indices[0] < users.size()) {
            Set<String> userPermutation = new HashSet<>();
            for (int index : indices) {
                userPermutation.add(users.get(index));
            }

            indices = plusOne(indices, indices.length - 1, users.size());

            if (userPermutation.size() != minUserCount) continue;
            if (alreadyProcessed.contains(userPermutation)) continue;

            if (isConstraintSatisfied(approvalsNeeded, userPermutation)) {
                winners.addAll(userPermutation);
            }
            alreadyProcessed.add(userPermutation);
        }

        if (!winners.isEmpty()) {
            return new ApproverList(minUserCount, winners);
        } else {
            return findSmallestSetOfUsers(approvalsNeeded, users, minUserCount + 1);
        }
    }

    private static int[] plusOne(int[] input, int position, int max) {
        input[position]++;
        if (input[position] >= max && position > 0) {
            input[position] = 0;
            input = plusOne(input, position - 1, max);
        }
        return input;
    }

    private static boolean isConstraintSatisfied(List<ApproverList> approvalsNeeded, Set<String> approvalsGiven) {
        approvalsNeeded = new ArrayList<>(approvalsNeeded);

        for (String approvalGiven : approvalsGiven) {
            for (int i = 0; i < approvalsNeeded.size(); i++) {
                if (approvalsNeeded.get(i).possibleApprovers().contains(approvalGiven)) {
                    int approvalsLeft = approvalsNeeded.get(i).minimumApproverCount() - 1;

                    if (approvalsLeft <= 0) {
                        approvalsNeeded.remove(i);
                        i--;
                    } else {
                        approvalsNeeded.set(i, new ApproverList(approvalsLeft, approvalsNeeded.get(i).possibleApprovers()));
                    }
                }
            }
        }

        return approvalsNeeded.isEmpty();
    }
}
