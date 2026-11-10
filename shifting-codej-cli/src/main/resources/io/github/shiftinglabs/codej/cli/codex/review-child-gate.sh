#!/bin/sh
# Codex supplies agent_id to PreToolUse for child calls. Its task MCP token does not.
# Children read and communicate; every tool that changes something stays with the root.

payload=$(/bin/cat)
name=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_name raw -o - - 2>/dev/null) || {
  echo "This tool call could not be read, so it was refused." >&2
  exit 2
}
printf '%s' "$payload" | /usr/bin/plutil -extract agent_id raw -o /dev/null - 2>/dev/null || exit 0
case "$name" in
  @CHILD_TOOLS@) exit 0 ;;
  Bash|write_stdin) exit 0 ;;
  collaborationsend_message|send_message)
    target=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_input.target raw -o - - 2>/dev/null)
    root=$(printf '%s' "$payload" | /usr/bin/plutil -extract session_id raw -o - - 2>/dev/null)
    [ "$target" = "/root" ] && exit 0
    [ -n "$root" ] && [ "$target" = "$root" ] && exit 0
    ;;
esac
echo "Subagent scope: use the tools listed for subagents, sandboxed source searches, or messages to the main agent. Leave changes and permission requests to the main agent. Return missing evidence in your final answer; do not retry denied calls." >&2
exit 2
