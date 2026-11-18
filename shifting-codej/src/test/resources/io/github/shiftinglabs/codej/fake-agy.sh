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
        case " $* " in *' --agent codej-reader '*) ;; *) exit 1 ;; esac
        case " $* " in *' --include-default-customizations=false '*) ;; *) exit 1 ;; esac ;;
    *) exit 1 ;;
esac

session_id=a8ebd962-55a4-4725-b912-cccdf872555f
opened_by=
selected_model=
text_update() {
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"%s"}}}}\n' "$session_id" "$1"
}

while IFS= read -r frame; do
    # Client requests serialize their numeric id before the method and params.
    after_id=${frame#*'"id":'}
    id=${after_id%%,*}
    result='{}'
    case "$frame" in
        *'"method":"initialize"'*)
            result='{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"resume":{},"close":{}}}}' ;;
        *'"method":"session/new"'*)
            opened_by=new
            result='{"sessionId":"'$session_id'","models":{"currentModelId":"default","availableModels":[{"modelId":"gemini-test","name":"Gemini Test"}]}}' ;;
        *'"method":"session/resume"'*)
            # Accept every ID here so the public test detects whether the host checks its own receipt.
            opened_by=resume ;;
        *'"method":"session/set_model"'*)
            case "$frame" in *'"modelId":"gemini-test"'*) selected_model=gemini-test ;; *) exit 1 ;; esac ;;
        *'"method":"session/prompt"'*'"text":"first"'*)
            [ "$opened_by" = new ] && [ "$selected_model" = gemini-test ] && [ "$GEMINI_API_KEY" = fixture-key-first ] || exit 1
            printf '{"jsonrpc":"2.0","id":"permission-1","method":"session/request_permission","params":{"sessionId":"%s","toolCall":{"toolCallId":"edit","kind":"edit","title":"Edit file"},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}\n' "$session_id"
            IFS= read -r decision
            case "$decision" in *'"id":"permission-1"'*'"outcome":"selected"'*'"optionId":"deny"'*) ;; *) exit 1 ;; esac
            text_update "Hello ${GEMINI_API_KEY%-first}"
            text_update "-${GEMINI_API_KEY##*-}"
            result='{"stopReason":"end_turn","usage":{"inputTokens":6,"cachedReadTokens":4,"cachedWriteTokens":0,"outputTokens":7,"thoughtTokens":5,"totalTokens":17}}' ;;
        *'"method":"session/prompt"'*'"text":"hold"'*)
            [ "$opened_by" = resume ] && [ "$GEMINI_API_KEY" = fixture-key-second ] || exit 1
            text_update "$$"
            continue ;;
        *'"method":"session/cancel"'*) continue ;;
        *'"method":"session/close"'*) ;;
        *) exit 1 ;;
    esac
    printf '{"jsonrpc":"2.0","id":%s,"result":%s}\n' "$id" "$result"
done
