#!/usr/bin/env bash
# Device lock protocol from .docs/agents/risk-based-evidence-policy.md.
# usage: device-lock.sh acquire|release|status s23u|s21 <agent-id>
set -euo pipefail
action=$1 phone=$2 agent=${3:-}
lock=/tmp/jandal-$phone.lock
case $action in
  acquire)
    [[ -n $agent ]] || { echo "agent id required" >&2; exit 2; }
    if ! mkdir "$lock" 2>/dev/null; then
      echo "HELD: $(tr '\n' ' ' < "$lock/owner" 2>/dev/null)" >&2
      exit 1
    fi
    printf '%s\n%s\n' "$agent" "$(date +%s)" > "$lock/owner"
    echo "ACQUIRED $lock"
    ;;
  release)
    owner=$(head -n1 "$lock/owner" 2>/dev/null || true)
    [[ $owner == "$agent" ]] || { echo "not owner ($owner)" >&2; exit 1; }
    rm -rf "$lock"
    echo "RELEASED $lock"
    ;;
  status)
    if [[ -d $lock ]]; then echo "HELD: $(tr '\n' ' ' < "$lock/owner")"; else echo FREE; fi
    ;;
  *) echo "bad action" >&2; exit 2 ;;
esac
