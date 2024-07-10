# GitLab Merge Request Report Generator

This project generates HTML reports on GitLab merge requests on a specified group, with a general list (excluding drafts)
and a list for each encountered user with merge requests relevant to them.

The user-specific list includes:
- merge requests they have to fix: they opened it, and either they are not labelled "review needed", have a conflict, or have a failing pipeline
- merge requests from others that are labelled "review needed", with them in the possible approvers list

The HTML files are output in `/tmp/mr_report_generator`, with the general list being called `all.html` and user-specific
lists called `<username>.html`.

This repository also has a GitHub action to zip up all the resulting HTML files, and send them as a POST to a specified URL.

The following environment variables / GitHub Actions secrets should be defined:
- `GITLAB_ACCESS_TOKEN`: a GitLab personal access token with the `read_api` scope, that has access to the targeted repositories
- `GITLAB_GROUP_ID`: the ID of the group to report on
- `GITLAB_NEEDS_REVIEW_LABELS`: a comma-separated list of labels that tag merge request that need reviews
- `GITLAB_TIMEZONE`: the tz database timezone to use for the last updated date on the reports
- `GITLAB_IGNORED_PREFIXES`: A comma-separated list of project name prefixes to exclude from the report
- `GITLAB_POST_URL`: _(for the GitHub action only)_ the URL that should be called with the zipped HTML files
- `GITLAB_POST_BASIC_AUTH`: _(for the GitHub action only)_ the Basic auth (`username:password`) to use when calling the post URL

## Merge request leaderboard

The `MergeRequestLeaderboardGenerator` class, when run, will compile a leaderboard based on the events that happened during the last month,
and post it on Slack. Each user gets points as follows:
- 2 points per comment
- 1 point per opened merge request
- 1 point per approval


The following environment variables / GitHub Actions secrets should be defined:
- `GITLAB_ACCESS_TOKEN`: a GitLab personal access token with the `read_api` scope, that has access to the targeted repositories
- `GITLAB_GROUP_ID`: the ID of the group to report on
- `GITLAB_IGNORED_PREFIXES`: A comma-separated list of project name prefixes to ignore when building the leaderboard
- `GITLAB_EXCLUDED_NICKNAMES`: A comma-separated list of display names to exclude from the leaderboard
- `GITLAB_SLACK_TOKEN`: the bot token that will be used to post the leaderboard
- `GITLAB_SLACK_CHANNEL`: the channel the leaderboard will be posted to

## Merge request auto-labeler

This class labels all merge requests in a group automatically, based on the amount of approvals there were given from a specific group.

The following environment variables / GitHub Actions secrets should be defined:
- `GITLAB_ACCESS_TOKEN`: a GitLab personal access token with the `api` scope, that has access to the targeted repositories
- `GITLAB_GROUP_ID`: the ID of the group to report on
- `GITLAB_IGNORED_PREFIXES`: a comma-separated list of project name prefixes to ignore when building the leaderboard
- `GITLAB_LABEL_GROUP_NAME`: the group of people that should be counted for the label
- `GITLAB_LABEL_NAME`: the name of the label (the label that will be applied is <label_name>::<approver_count>)
