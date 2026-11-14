#!/usr/bin/env bash
# PreToolUse hook for read-only agents (e.g. .claude/agents/code-reviewer.md).
# Reads the hook JSON on stdin and allows a Bash command only if every
# pipeline segment is a known read-only command. Exit 2 blocks the call and
# the message on stderr is shown to the agent.
set -u

deny() { echo "Blocked by read-only hook: $1" >&2; exit 2; }

command -v jq >/dev/null 2>&1 || deny "jq is not installed, so commands cannot be checked."

cmd=$(jq -r '.tool_input.command // empty') || deny "could not parse hook input."
[ -n "$cmd" ] || deny "empty command."

# No chaining, redirection, substitution, or multi-line scripts.
case "$cmd" in
  *';'*|*'&'*|*'>'*|*'<'*|*'`'*|*'$('*|*$'\n'*)
    deny "only simple read-only commands are allowed (no ; & > < \` \$( or newlines)." ;;
  *--output*)
    deny "writing output to a file is not allowed." ;;
esac

IFS='|' read -r -a segments <<< "$cmd"
for seg in "${segments[@]}"; do
  seg="${seg#"${seg%%[![:space:]]*}"}"   # trim leading space
  # Treat "git -C <path> <subcommand>" like "git <subcommand>".
  if [[ "$seg" =~ ^git[[:space:]]+-C[[:space:]]+[^[:space:]]+[[:space:]]+(.*)$ ]]; then
    seg="git ${BASH_REMATCH[1]}"
  fi
  case "$seg" in
    "git diff"*|"git log"*|"git show"*|"git status"*|"git blame"*|"git grep"*|\
    "git rev-parse"*|"git merge-base"*|"git ls-files"*|\
    "gh pr view"*|"gh pr diff"*|"gh pr checks"*|"gh issue view"*|\
    "grep "*|"rg "*|"cat "*|"head"*|"tail"*|"wc"*|"ls"*|"sort"*|"uniq"*)
      ;;
    "find "*)
      case "$seg" in
        *-exec*|*-execdir*|*-ok*|*-delete*|*-fprint*) deny "find with actions is not allowed." ;;
      esac ;;
    *) deny "'${seg%% *}' is not on the read-only allowlist." ;;
  esac
done
exit 0
