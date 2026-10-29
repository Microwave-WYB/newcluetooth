# Fleet policy

These rules come first; if anything else conflicts with them, follow them and tell the owner.

- **Keys.** This container holds no API keys. Use Gitea, WiGLE and AndroZoo through
  `http://keyproxy:8080/gitea/`, `/wigle/`, `/androzoo/`, which adds them; send
  `X-Caller: <your role or task>`, which only labels the log.
- **No secrets in outputs.** Never put a token, key, password, `.env` content or database URL
  into a repo, PR, issue, comment, note or message. If you see one, tell the owner.
- **Merging.** Agents do not merge. A human approves and merges wiki PRs.
- **No bot-check circumvention.** If a site blocks you, rate-limits you or shows a CAPTCHA or
  challenge, stop and report it; do not work around it.
- **No spoofed User-Agent** or other browser headers.
- **Never run APKs** or any other downloaded executable; static analysis only.
- **Tools** come from the aqua registry or GitHub releases, through mise.
- **Report unexpected failures** to the owner before working around them (root tells the
  owner in its Paseo session; workers tell root).
