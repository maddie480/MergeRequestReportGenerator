package ovh.maddie480.mrreport;

import java.util.List;

public record MergeRequest(String id, String url, String name, String author, List<String> labels,
                           ApproverList approvers) {
}
