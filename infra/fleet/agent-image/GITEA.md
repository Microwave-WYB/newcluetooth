# Gitea recipes (this repo has no GitHub; use these instead of gh)

Gitea is reached only through keyproxy, which adds the bot's token. There is no token in this
container: do not set one, and do not send `Authorization` headers.

Environment: `GITEA_URL=http://keyproxy:8080/gitea`, `GITEA_REPO=y2wei/wirelesswiki`.
Set `X-Caller` to who you are (`root`, `worker:task-<n>`); it labels the proxy log only.

    api() { curl -s -H "X-Caller: ${CALLER:-unknown}" -H "Content-Type: application/json" "$@"; }

- Open an issue:   api -X POST "$GITEA_URL/api/v1/repos/$GITEA_REPO/issues" -d '{"title":"...","body":"..."}'
- Comment:         api -X POST "$GITEA_URL/api/v1/repos/$GITEA_REPO/issues/<n>/comments" -d '{"body":"..."}'
- List issues:     api "$GITEA_URL/api/v1/repos/$GITEA_REPO/issues?state=open&type=issues&limit=50"
- Push a branch:   git push -u origin task/<n>
- Open a PR:       api -X POST "$GITEA_URL/api/v1/repos/$GITEA_REPO/pulls" -d '{"head":"task/<n>","base":"main","title":"...","body":"Closes #<n>\n..."}'
- List PRs:        api "$GITEA_URL/api/v1/repos/$GITEA_REPO/pulls?state=open"
- PR files / diff: api "$GITEA_URL/api/v1/repos/$GITEA_REPO/pulls/<n>/files", "$GITEA_URL/api/v1/repos/$GITEA_REPO/pulls/<n>.diff"

`git` rewrites `http://gitea:3000/` to `http://keyproxy:8080/gitea/`, so existing clones keep
working. Pushes are accepted only for `task/*` branches. Merging, reviews, closing, editing,
labels changes and settings are not available: ask a human in the issue.

Owner UI: https://git.wirelesswiki.org (behind Cloudflare Access). Humans approve and merge.
