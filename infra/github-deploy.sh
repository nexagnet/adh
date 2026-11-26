#!/usr/bin/env bash
# Forced command for the two ADH-only GitHub Actions keys. Install next to the
# existing Compose configuration on each host. Never evaluate the SSH command.
set -euo pipefail

role="${1:-}"
case "$role" in
    main|bridge2) ;;
    *) exit 2 ;;
esac

if [[ ! "${SSH_ORIGINAL_COMMAND:-}" =~ ^deploy\ ([a-f0-9]{40})$ ]]; then
    echo 'Expected deploy followed by a full commit SHA.' >&2
    exit 2
fi
sha="${BASH_REMATCH[1]}"
cd "$(dirname "${BASH_SOURCE[0]}")"

main="$(timeout 30 git ls-remote https://github.com/nexagnet/adh.git refs/heads/main | cut -f1)"
if [[ "$sha" != "$main" ]]; then
    echo 'The requested release is no longer main.' >&2
    exit 1
fi

if [[ "$role" == main ]]; then
    main_ready() {
        [[ "$(cat .current-tag 2>/dev/null || true)" == "$sha" ]] &&
            [[ "$(docker inspect --format '{{.Config.Image}} {{.State.Status}}' adh-app-1)" == "ghcr.io/nexagnet/adh-app:$sha running" ]] &&
            [[ "$(docker inspect --format '{{.Config.Image}} {{.State.Status}}' adh-zalo-bridge-1)" == "ghcr.io/nexagnet/adh-zalo-bridge:$sha running" ]] &&
            [[ "$(curl -fsS -m 5 http://127.0.0.1:8081/login -o /dev/null -w '%{http_code}')" == 200 ]] &&
            docker exec adh-postgres-1 pg_isready -q &&
            docker exec adh-zalo-bridge-1 node -e 'fetch("http://127.0.0.1:3100/healthz",{signal:AbortSignal.timeout(5000)}).then(async r=>{if(!r.ok||(await r.json()).ok!==true)process.exitCode=1}).catch(()=>process.exitCode=1)'
    }
    # The existing path verifies main CI, checks resources, backs up the DB and
    # rolls the app back on failed readiness. Cron and CD share its lock.
    bash ./auto-deploy.sh
    for _ in {1..120}; do
        if main_ready; then
            echo "ADH main is running $sha"
            exit 0
        fi
        [[ "$(cat .auto-failed-tag 2>/dev/null || true)" != "$sha" ]] || break
        # A cron run may have won the lock. A free lock with a different tag
        # means the release was deferred or refused, not deployed.
        if flock -n .auto-deploy.lock true; then
            main_ready || break
            echo "ADH main is running $sha"
            exit 0
        fi
        sleep 5
    done
    echo 'Could not verify the requested release on ADH main.' >&2
    exit 1
fi

exec 9>.github-deploy.lock
flock -w 120 9
image=ghcr.io/nexagnet/adh-zalo-bridge
service=zalo-bridge
project=adh-zalo-bridge-2
compose() {
    ADH_TAG="$1" docker compose --project-name "$project" -f compose.yml --env-file .env "${@:2}"
}

previous="$(sed -n 's/^ADH_TAG=\([a-f0-9]\{40\}\)$/\1/p' .env)"
[[ "$previous" =~ ^[a-f0-9]{40}$ ]] || { echo 'Invalid or duplicate ADH_TAG in bridge .env.' >&2; exit 1; }
compose "$sha" config --quiet

ready() {
    local container
    container="$(compose "$1" ps --quiet "$service")" &&
        [[ -n "$container" ]] &&
        [[ "$(docker inspect --format '{{.Config.Image}} {{.State.Status}}' "$container")" == "$image:$1 running" ]] &&
        curl -fsS -m 5 http://127.0.0.1:3100/healthz | python3 -c 'import json, sys; sys.exit(json.load(sys.stdin).get("ok") is not True)'
}

if [[ "$sha" == "$previous" ]] && ready "$sha"; then
    echo "ADH bridge 2 is already running $sha"
    exit 0
fi

compose "$sha" pull "$service"
tmp="$(mktemp .env.deploy.XXXXXX)"
trap 'rm -f "$tmp"' EXIT
sed "s/^ADH_TAG=$previous$/ADH_TAG=$sha/" .env >"$tmp"
if compose "$sha" up -d --no-deps "$service"; then
    for _ in {1..24}; do
        if ready "$sha"; then
            mv -f "$tmp" .env
            echo "ADH bridge 2 is running $sha"
            exit 0
        fi
        sleep 5
    done
fi

echo 'New bridge did not become ready. Restoring the previous ADH bridge.' >&2
compose "$previous" up -d --no-deps "$service"
exit 1
