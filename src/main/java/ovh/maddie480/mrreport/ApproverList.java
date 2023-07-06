package ovh.maddie480.mrreport;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

public record ApproverList(int minimumApproverCount, Set<String> possibleApprovers) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}