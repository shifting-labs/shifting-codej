#!/bin/sh
# Local ACP fixture. All prompts, request IDs and response strings used here have known forms.
set -eu

case "$1" in
    --version)
        echo '1.5.10'
        exit ;;
    agents)
        case "${GEMINI_API_KEY:-}" in
            missing-reader) echo 'other-reader' ;;
            *) echo 'codej-reader' ;;
        esac
        exit ;;
    models)
        case "${GEMINI_API_KEY:-}" in
            '') echo 'Please sign in to view available models' >&2; exit 1 ;;
            invalid-fixture-key) echo "Rejected API key $GEMINI_API_KEY" >&2; exit 1 ;;
        esac
        printf 'gemini-test\tGemini Test\n'
        exit ;;
    acp)
        case " $* " in *' --sandbox '*) ;; *) exit 1 ;; esac
        case " $* " in *' --include-default-customizations=false '*) ;; *) exit 1 ;; esac
        launch=" $* "
        agent=
        while [ "$#" -gt 0 ]; do
            if [ "$1" = --agent ]; then agent=$2; shift; fi
            shift
        done
        case "$agent" in codej-reader*|codej-writer*|codej-tools) ;; *) exit 1 ;; esac
        agent_file="$HOME/.gemini/config/agents/$agent/agent.md" ;;
    *) exit 1 ;;
esac

session_id=fixture-$$
opened_by=
selected_model=
selected_effort=
mode=
elicitation=
endpoint=
authorization=
instructions=
config_options='[{"id":"effort","category":"thought_level","type":"select","currentValue":"medium","options":[]}]'
text_update() {
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"%s"}}}}\n' "$session_id" "$1"
}

fixture_error() {
    printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32603,"message":"%s"}}\n' "$id" "$1"
    exit 1
}

permission() {
    printf '{"jsonrpc":"2.0","id":"permission-1","method":"session/request_permission","params":{"sessionId":"%s","toolCall":{"toolCallId":"edit","kind":"edit","title":"Edit file","locations":[{"path":"%s"}]},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}\n' "$session_id" "$1"
    IFS= read -r decision
    case "$decision" in *'"id":"permission-1"'*'"outcome":"selected"'*'"optionId":"'"$2"'"'*) ;; *) fixture_error "Unexpected decision for $1" ;; esac
}

mcp_call() {
    curl --max-time 15 -fsS -H "Authorization: $authorization" -H "Content-Type: application/json" \
        --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":'"$1"'}' "$endpoint"
}

command_tool() {
    reply=$(mcp_call '{"name":"codej_run_command","arguments":{"argv":'"$1"'}}')
    if ! printf '%s\n' "$reply" | grep -qF "$2"; then
        command=$(printf '%s' "$1" | tr -cd '[:alnum:] ./,:' | cut -c1-100)
        diagnostic=$(printf '%s' "$reply" | tr -cd '[:alnum:] ./,:' | tail -c 360)
        fixture_error "Unexpected result for $command. $diagnostic"
    fi
}

while IFS= read -r frame; do
    # Client requests serialize their numeric id before the method and params.
    after_id=${frame#*'"id":'}
    id=${after_id%%,*}
    result='{}'
    case "$frame" in
        *'"method":"initialize"'*)
            case "$frame" in *'"elicitation":{"form":{}}'*) elicitation=enabled ;; esac
            result='{"protocolVersion":1,"agentCapabilities":{"mcpCapabilities":{"http":true},"sessionCapabilities":{"resume":{},"close":{}},"_meta":{"antigravity":{"systemPrompt":{"append":true}}}}}' ;;
        *'"method":"session/new"'*)
            opened_by=new
            result='{"sessionId":"'$session_id'","models":{"currentModelId":"default","availableModels":[{"modelId":"gemini-test","name":"Gemini Test"}]},"configOptions":'$config_options'}' ;;
        *'"method":"session/resume"'*)
            # Accept every ID here so the public test detects whether the host checks its own receipt.
            opened_by=resume
            session_id=$(printf '%s' "$frame" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p') ;;
        *'"method":"session/set_mode"'*)
            mode=$(printf '%s' "$frame" | sed -n 's/.*"modeId":"\([^"]*\)".*/\1/p') ;;
        *'"method":"session/set_model"'*)
            case "$frame" in *'"modelId":"gemini-test"'*) selected_model=gemini-test ;; *) exit 1 ;; esac ;;
        *'"method":"session/set_config_option"'*)
            case "$frame" in *'"configId":"effort"'*'"value":"medium"'*) selected_effort=medium ;; *) exit 1 ;; esac
            result='{"configOptions":'$config_options'}' ;;
        *'"method":"session/prompt"'*'"text":"first"'*)
            [ "$opened_by" = new ] && [ "$selected_model" = gemini-test ] && [ "$selected_effort" = medium ] && [ "$GEMINI_API_KEY" = fixture-key-first ] || exit 1
            permission protected.txt deny
            printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Ignore thought"}}}}\n' "$session_id"
            printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"resource","text":"Ignore resource"}}}}\n' "$session_id"
            printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"tool_call","toolCallId":"read-1","title":"Read file","status":"pending"}}}\n' "$session_id"
            printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"tool_call_update","toolCallId":"read-1","status":"in_progress"}}}\n' "$session_id"
            printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"tool_call_update","toolCallId":"read-1","status":"completed"}}}\n' "$session_id"
            text_update "Hello ${GEMINI_API_KEY%-first}"
            text_update "-${GEMINI_API_KEY##*-}"
            result='{"stopReason":"end_turn","usage":{"inputTokens":6,"cachedReadTokens":4,"outputTokens":7,"thoughtTokens":5,"totalTokens":17}}' ;;
        *'"method":"session/prompt"'*'"text":"invalid-usage"'*)
            text_update "Completed with invalid usage"
            result='{"stopReason":"end_turn","usage":{"inputTokens":9223372036854775808,"outputTokens":7}}' ;;
        *'"method":"session/prompt"'*'"text":"write-auto"'*)
            [ "$agent" = codej-writer ] && [ "$mode" = accept-edits ] || exit 1
            printf 'automatic\n' > auto.txt
            text_update "Automatic edit"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"write-reviewed"'*)
            [ "$agent" = codej-writer ] && [ "$mode" = default ] || fixture_error "Expected workspace-write review mode"
            permission allowed.txt allow
            printf 'edited\n' > allowed.txt || fixture_error "Checkout edit failed"
            permission denied.txt deny
            permission ../outside.txt deny
            # These writes ignore the ACP policy to exercise the independent OS boundary.
            if (printf 'unexpected\n' > ../outside.txt) 2>/dev/null; then fixture_error "Outside edit succeeded"; fi
            if (printf 'unexpected\n' > outside-link) 2>/dev/null; then fixture_error "Symlink edit succeeded"; fi
            if (printf 'unexpected\n' > .git/config) 2>/dev/null; then fixture_error "Git metadata edit succeeded"; fi
            text_update "Reviewed edit"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"use-host-tool"'*)
            case "$launch" in *' --process-per-session '*) ;; *) exit 1 ;; esac
            [ "$elicitation" = enabled ] && [ -n "$endpoint" ] && [ -n "$authorization" ] || exit 1
            [ "$instructions" = "Use the host lookup tool." ] || fixture_error "Missing system instructions"
            grep -qF '  - ask_question' "$agent_file"
            case "$frame" in *'Use the host lookup tool.'*) exit 1 ;; esac
            permission protected.txt deny
            if (printf 'unexpected\n' > protected.txt) 2>/dev/null; then exit 1; fi
            for denied in denied-read.txt denied-alias; do
                if cat "$denied" >/dev/null 2>&1; then fixture_error "Read a denied file"; fi
            done
            reply=$(curl --max-time 5 -fsS -H "Authorization: $authorization" -H "Content-Type: application/json" \
                --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"lookup","arguments":{"key":"alpha"}}}' "$endpoint")
            case "$reply" in *'"text":"looked up alpha"'*) ;; *) exit 1 ;; esac
            printf '{"jsonrpc":"2.0","id":91,"method":"elicitation/create","params":{"sessionId":"%s","mode":"form","message":"Choose a plan","requestedSchema":{"type":"object","properties":{"plan":{"type":"string","title":"Which plan?","oneOf":[{"const":"fast","title":"Fast plan"},{"const":"slow","title":"Slow plan"}]}},"required":["plan"]}}}\n' "$session_id"
            IFS= read -r answer
            case "$answer" in *'"id":91,'*'"action":"accept"'*'"plan":"fast"'*) ;; *) exit 1 ;; esac
            text_update "Tool and question complete"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"run-commands"'*)
            command_tool '["/usr/bin/make","test"]' '"exitCode\":0'
            command_tool '["/bin/cp","built.txt","../granted/copied.txt"]' '"exitCode\":0'
            command_tool '["/bin/cp","built.txt","../outside.txt"]' '"exitCode\":1'
            command_tool '["/usr/bin/touch","denied-command.txt"]' 'Command is denied'
            command_tool '["/bin/cp","built.txt","unapproved.txt"]' 'Command approval was denied'
            command_tool '["/bin/cat","denied-read.txt"]' '"exitCode\":1'
            command_tool '["/usr/bin/printenv","GEMINI_API_KEY"]' '"exitCode\":1'
            text_update "Commands complete"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"command-network-'*)
            expected='"exitCode\":0'
            case "$frame" in *'"text":"command-network-off"'*) expected='"exitCode\":7' ;; esac
            command_tool '["/usr/bin/curl","--noproxy","*","--max-time","3","-s","-o","/dev/null","'"$endpoint"'"]' "$expected"
            text_update "Network check complete"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"hold-command"'*)
            mcp_call '{"name":"codej_run_command","arguments":{"argv":["/bin/sh","-c","echo $$ > command.tmp; /bin/mv command.tmp command.pid; exec /bin/sleep 90"]}}' >/dev/null 2>&1 &
            continue ;;
        *'"method":"session/prompt"'*'"text":"local-commit"'*)
            command_tool '["git","add","change.txt"]' '"exitCode\":0'
            command_tool '["git","commit","-m","Local task"]' '"exitCode\":0'
            command_tool '["git","update-ref","refs/remotes/origin/main","HEAD"]' '"exitCode\":128'
            command_tool '["/bin/cp","change.txt","../main/foreign.txt"]' '"exitCode\":1'
            text_update "Local commit complete"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"tools-only"'*|*'"method":"session/prompt"'*'"text":"text-only"'*)
            [ "$agent" = codej-tools ] && [ -z "$mode" ] || fixture_error "Expected tools-only agent"
            grep -qF 'tools: []' "$agent_file" && grep -qF 'commandExecutionPolicy: off' "$agent_file"
            if cat protected.txt >/dev/null 2>&1; then fixture_error "Tools-only checkout read succeeded"; fi
            if (printf 'unexpected\n' > protected.txt) 2>/dev/null; then fixture_error "Tools-only checkout write succeeded"; fi
            case "$frame" in
                *'"text":"tools-only"'*)
                    reply=$(mcp_call '{"name":"lookup","arguments":{"key":"alpha"}}')
                    case "$reply" in *'"text":"looked up alpha"'*) ;; *) fixture_error "Host tool failed" ;; esac
                    command_tool '["/usr/bin/true"]' 'Unknown tool' ;;
                *) [ -z "$endpoint" ] || fixture_error "Unexpected MCP endpoint" ;;
            esac
            text_update "Tools-only check complete"
            result='{"stopReason":"end_turn"}' ;;
        *'"method":"session/prompt"'*'"text":"hold-approval"'*)
            permission pending.txt deny
            continue ;;
        *'"method":"session/prompt"'*'"text":"hold"'*)
            [ "$opened_by" = resume ] && [ "$GEMINI_API_KEY" = fixture-key-second ] || exit 1
            text_update "$$"
            continue ;;
        *'"method":"session/cancel"'*) continue ;;
        *'"method":"session/close"'*) ;;
        *) exit 1 ;;
    esac
    case "$frame" in
        *'"mcpServers":[{'*'"name":"codej_session"'*)
            endpoint=$(printf '%s' "$frame" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
            authorization=$(printf '%s' "$frame" | sed -n 's/.*"name":"Authorization","value":"\([^"]*\)".*/\1/p') ;;
    esac
    case "$frame" in
        *'"_meta":{"antigravity":{"systemPrompt":{"append":'*)
            instructions=$(printf '%s' "$frame" | sed -n 's/.*"append":"\([^"]*\)".*/\1/p') ;;
    esac
    printf '{"jsonrpc":"2.0","id":%s,"result":%s}\n' "$id" "$result"
done
