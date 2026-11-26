#!/usr/bin/env bash
set -euo pipefail

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cp "$(dirname "${BASH_SOURCE[0]}")/github-deploy.sh" "$fixture/"
mkdir "$fixture/bin"
export PATH="$fixture/bin:$PATH"
export TEST_FIXTURE="$fixture"
export TEST_MAIN=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
old=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf 'ADH_TAG=%s\n' "$old" >"$fixture/.env"
printf '%s' "$old" >"$fixture/running"

cat >"$fixture/bin/git" <<'EOF'
#!/usr/bin/env bash
printf '%s\trefs/heads/main\n' "$TEST_MAIN"
EOF
cat >"$fixture/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -eu
printf '%s %s\n' "${ADH_TAG:-}" "$*" >>"$TEST_FIXTURE/calls"
case "$1" in
    inspect) printf 'ghcr.io/nexagnet/adh-zalo-bridge:%s running\n' "$(cat "$TEST_FIXTURE/running")" ;;
    compose)
        [[ "$2" == --project-name && "$3" == adh-zalo-bridge-2 && "$5" == compose.yml ]] || exit 90
        case "$8" in
            config|pull) ;;
            ps) echo fixture-container ;;
            up)
                [[ "$*" == *'--no-deps zalo-bridge' ]] || exit 91
                [[ "${TEST_UP_FAIL:-}" != "$ADH_TAG" ]] || exit 92
                printf '%s' "$ADH_TAG" >"$TEST_FIXTURE/running" ;;
            *) exit 93 ;;
        esac ;;
    *) exit 94 ;;
esac
EOF
cat >"$fixture/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo '{"ok":true}'
EOF
chmod +x "$fixture/bin/"*

for request in 'status' "deploy $TEST_MAIN --force" "deploy $old"; do
    if SSH_ORIGINAL_COMMAND="$request" bash "$fixture/github-deploy.sh" bridge2; then
        echo "Accepted an invalid or stale release request." >&2
        exit 1
    fi
done
[[ ! -e "$fixture/calls" ]]

if TEST_UP_FAIL="$TEST_MAIN" SSH_ORIGINAL_COMMAND="deploy $TEST_MAIN" bash "$fixture/github-deploy.sh" bridge2; then
    echo "Reported a failed release as deployed." >&2
    exit 1
fi
grep -qx "ADH_TAG=$old" "$fixture/.env"
[[ "$(cat "$fixture/running")" == "$old" ]]

SSH_ORIGINAL_COMMAND="deploy $TEST_MAIN" bash "$fixture/github-deploy.sh" bridge2
grep -qx "ADH_TAG=$TEST_MAIN" "$fixture/.env"
[[ "$(cat "$fixture/running")" == "$TEST_MAIN" ]]
count="$(grep -c ' up ' "$fixture/calls")"
SSH_ORIGINAL_COMMAND="deploy $TEST_MAIN" bash "$fixture/github-deploy.sh" bridge2
[[ "$(grep -c ' up ' "$fixture/calls")" == "$count" ]]
echo 'GitHub deploy command, scoped rollout, rollback and idempotency passed.'
