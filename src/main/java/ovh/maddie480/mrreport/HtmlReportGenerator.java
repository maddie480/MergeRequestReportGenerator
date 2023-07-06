package ovh.maddie480.mrreport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

public class HtmlReportGenerator {
    public static void generate(String title, Map<String, List<MergeRequest>> contents, Map<String, String> usernamesToNames, Path destination) throws IOException {
        try (OutputStream os = Files.newOutputStream(destination);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            out.write("""
                    <!DOCTYPE html>

                    <html lang="en">
                    <head>
                        <title>"""
                    + escapeHtml4(title) + """
                        </title>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <meta http-equiv="refresh" content="60" >

                        <script src="https://maddie480.ovh/js/dark-mode.js"></script>
                        <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"
                            rel="stylesheet" integrity="sha384-9ndCyUaIbzAi2FUVXJi0CjmCapSmO7SnpJef0486qhLnuZ2cdeRhO02iuK6FUUVM" crossorigin="anonymous">
                    </head>

                    <body>
                        <div class="container">
                    """);

            out.write("<h1>" + escapeHtml4(title) + "</h1>");
            out.write("<p>Last refreshed on " + escapeHtml4(ZonedDateTime.now(ZoneId.of(System.getenv("GITLAB_TIMEZONE")))
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG))) + "</p>");

            for (Map.Entry<String, List<MergeRequest>> project : contents.entrySet()) {
                out.write("<h2>Merge requests in " + escapeHtml4(project.getKey()) + "</h2>");

                out.write("""
                        <table class="table table-striped">
                            <tr>
                                <th>#</th>
                                <th>Name</th>
                                <th>Author</th>
                                <th>Labels</th>
                                <th>Reviewers needed</th>
                            </tr>
                        """);

                for (MergeRequest mr : project.getValue()) {
                    out.write("<tr>");
                    out.write("<td>" + escapeHtml4(mr.id()) + "</td>");
                    out.write("<td><a href=\"" + escapeHtml4(mr.url()) + "\" target=\"_blank\">" + escapeHtml4(mr.name()) + "</a></td>");
                    out.write("<td>" + escapeHtml4(mr.author()) + "</td>");

                    out.write("<td>");
                    for (String label : mr.labels()) {
                        String style = "secondary";
                        if (Arrays.asList("conflict", "pipeline failed").contains(label)) {
                            style = "danger";
                        } else if (Arrays.asList(System.getenv("GITLAB_NEEDS_REVIEW_LABELS").split(",")).contains(label)) {
                            style = "primary";
                        }

                        out.write("<span class=\"badge bg-" + style + "\">" + escapeHtml4(label) + "</span>\n");
                    }
                    out.write("</td>");

                    out.write("<td>");
                    if (mr.approvers().minimumApproverCount() == 0) {
                        out.write("-");
                    } else {
                        out.write("<b>" + mr.approvers().minimumApproverCount() + "</b> among " + escapeHtml4(
                                mr.approvers().possibleApprovers().stream()
                                        .map(usernamesToNames::get)
                                        .collect(Collectors.joining(", "))
                        ));
                    }
                    out.write("</td>");

                    out.write("</tr>");
                }

                out.write("</table>");
            }

            out.write("""
                        </div>
                    </body>
                    </html>
                    """);
        }
    }
}
