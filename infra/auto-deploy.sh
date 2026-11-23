#!/usr/bin/env bash
# Tự cập nhật adh trên máy chủ DÙNG CHUNG: chạy bằng cron, chỉ triển khai khi bản mới nhất của main đã qua CI xanh
# và ảnh Docker của đúng commit đó đã có trên ghcr. Việc triển khai thật (sao lưu DB trước khi chạy migration, chờ ứng
# dụng khỏe, tự lùi ảnh) do deploy.sh làm; script này chỉ quyết định KHI NÀO được phép chạy và ghi lại kết quả.
#
#   ./auto-deploy.sh            một lượt kiểm tra (đây là lệnh đặt trong cron)
#   ./auto-deploy.sh --dry-run  chỉ cho biết sẽ làm gì, không đổi gì
#   ./auto-deploy.sh status     trạng thái, 15 dòng log cuối
#   ./auto-deploy.sh pause      TẠM DỪNG tự cập nhật (tạo file .auto-deploy-paused); cron vẫn chạy nhưng không làm gì
#   ./auto-deploy.sh resume     bật lại
#   ./auto-deploy.sh unblock    bỏ dấu "bản này đã lỗi, không thử lại" để cho thử lại cùng commit
#
# Không bao giờ: chạy khi đang có một lượt khác (khóa flock), chạy lần triển khai ĐẦU TIÊN (phải có .current-tag do
# deploy.sh tạo bằng tay), đụng vào container/ảnh của dự án khác (chỉ dọn ảnh ghcr.io/nexagnet/adh-app cũ).
# Biến (môi trường hoặc infra/.env; xem docs/RUNBOOK.md mục 2c):
#   ADH_AUTO_REPO=nexagnet/adh  ADH_AUTO_BRANCH=main  ADH_AUTO_GITHUB_TOKEN= (chỉ cần nếu repo riêng tư; quyền đọc Actions)
#   ADH_AUTO_LOG=<infra>/auto-deploy.log  ADH_AUTO_MIN_FREE_MB=250  ADH_AUTO_MIN_DISK_MB=1500  ADH_AUTO_MAX_ATTEMPTS=3
#   ADH_AUTO_NOTIFY_CMD (chỉ từ môi trường, KHÔNG đọc từ .env): lệnh nhận nội dung báo cáo ở tham số cuối, gọi khi có việc cần người xem.
set -uo pipefail
export PATH="${PATH:-/usr/bin:/bin}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/sbin"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# Đọc biến: ưu tiên môi trường, sau đó .env (đọc từng dòng, KHÔNG nạp file như mã để không chạy lệnh trong đó).
envval() { # $1 tên, $2 mặc định
    local v="${!1:-}"
    if [ -z "$v" ] && [ -f .env ]; then v="$(grep -E "^$1=" .env | tail -n1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' || true)"; fi
    printf '%s' "${v:-$2}"
}

REPO="$(envval ADH_AUTO_REPO nexagnet/adh)"
BRANCH="$(envval ADH_AUTO_BRANCH main)"
TOKEN="$(envval ADH_AUTO_GITHUB_TOKEN '')"
API="${ADH_AUTO_API:-https://api.github.com}"
LOG="$(envval ADH_AUTO_LOG "$HERE/auto-deploy.log")"
MIN_FREE_MB="$(envval ADH_AUTO_MIN_FREE_MB 250)"
MIN_DISK_MB="$(envval ADH_AUTO_MIN_DISK_MB 1500)"
MAX_ATTEMPTS="$(envval ADH_AUTO_MAX_ATTEMPTS 3)"
HOST_PORT="$(envval ADH_HOST_PORT 8081)"
IMAGE="ghcr.io/nexagnet/adh-app"
BRIDGE_IMAGE="ghcr.io/nexagnet/adh-zalo-bridge"

CUR=.current-tag; PREV=.previous-tag
LOCK=.auto-deploy.lock; PAUSED=.auto-deploy-paused
FAILED=.auto-failed-tag; ATTEMPTS=.auto-attempts; NOTE=.auto-last-note
NOTIFY_CMD="${ADH_AUTO_NOTIFY_CMD:-}"

log() {
    local line; line="$(date '+%F %T') $*"
    printf '%s\n' "$line" >>"$LOG" 2>/dev/null || true
    if [ -t 2 ]; then printf '%s\n' "$line" >&2; fi
    return 0
}
notify() {
    [ -n "$NOTIFY_CMD" ] || return 0
    # shellcheck disable=SC2086
    $NOTIFY_CMD "adh tự cập nhật: $*" >/dev/null 2>&1 || log "(không gọi được ADH_AUTO_NOTIFY_CMD)"
    return 0
}
# Ghi một dòng chỉ khi nội dung khác lần trước, để cron 10 phút một lần không làm đầy log. $1: info|warn (warn thì báo người).
note() {
    local last; last="$(cat "$NOTE" 2>/dev/null || true)"
    [ "$2" = "$last" ] && return 0
    log "$2"; printf '%s' "$2" >"$NOTE"
    if [ "$1" = warn ]; then notify "$2"; fi
    return 0
}
rotate_log() {
    if [ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 1048576 ]; then tail -n 3000 "$LOG" >"$LOG.tmp" && mv "$LOG.tmp" "$LOG"; fi
    return 0
}

if docker info >/dev/null 2>&1; then SUDO=""; else SUDO="sudo -n "; fi
snapshot() { # ghi lại tài nguyên để theo dõi máy nhỏ
    local ram swap disk names l
    ram="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
    swap="$(awk '/SwapFree/ {print int($2/1024)}' /proc/meminfo)"
    disk="$(df -Pm "$HERE" | awk 'NR==2 {print $4}')"
    log "tài nguyên: RAM trống ${ram} MB, swap trống ${swap} MB, đĩa trống ${disk} MB"
    # Chỉ hỏi container của adh; "docker stats" không tham số sẽ liệt kê cả dự án khác.
    names="$($SUDO docker ps --filter label=com.docker.compose.project=adh --format '{{.Names}}' 2>/dev/null | tr '\n' ' ')"
    if [ -n "${names// /}" ]; then
        # shellcheck disable=SC2086
        $SUDO docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}} ({{.MemPerc}}), CPU {{.CPUPerc}}' $names 2>/dev/null |
            while IFS= read -r l; do log "$l"; done
    fi
    return 0
}
healthy() {
    local code; code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$HOST_PORT/" || true)"
    [[ "$code" =~ ^[1-4][0-9][0-9]$ ]]
}
api() { # $1 đường dẫn sau /repos/<repo>/
    if [ -n "$TOKEN" ]; then
        printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" |
            curl -fsS -m 20 -K - -H 'Accept: application/vnd.github+json' "$API/repos/$REPO/$1"
    else
        curl -fsS -m 20 -H 'Accept: application/vnd.github+json' "$API/repos/$REPO/$1"
    fi
}
# In: success | pending:<lý do> | failed:<kết luận>. Lấy lần chạy "ci" (push vào nhánh chính) mới nhất của đúng commit.
ci_state() { # $1 sha
    api "actions/runs?head_sha=$1&per_page=50" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
runs = [r for r in d.get("workflow_runs", [])
        if str(r.get("path", "")).endswith("/ci.yml") and r.get("event") == "push" and r.get("head_branch") == sys.argv[1]]
if not runs:
    print("pending:chưa thấy lần chạy CI"); sys.exit()
r = max(runs, key=lambda r: (r.get("run_number", 0), r.get("run_attempt", 1)))
if r.get("status") != "completed":
    print("pending:CI đang " + str(r.get("status")))
elif r.get("conclusion") == "success":
    print("success")
else:
    print("failed:" + str(r.get("conclusion")))
' "$BRANCH"
}

case "${1:-run}" in
pause)   touch "$PAUSED"; log "TẠM DỪNG tự cập nhật (bằng tay). Gõ ./auto-deploy.sh resume để bật lại."; echo "Đã tạm dừng."; exit 0 ;;
resume)  rm -f "$PAUSED"; log "Bật lại tự cập nhật (bằng tay)."; echo "Đã bật lại."; exit 0 ;;
unblock) rm -f "$FAILED" "$ATTEMPTS" "$NOTE"; echo "Đã bỏ dấu lỗi; lượt cron sau sẽ thử lại."; exit 0 ;;
status)
    echo "Tạm dừng: $([ -f "$PAUSED" ] && echo CÓ || echo không)"
    echo "Thẻ đang chạy: $(cat "$CUR" 2>/dev/null || echo 'chưa có')   thẻ trước: $(cat "$PREV" 2>/dev/null || echo 'chưa có')"
    echo "Thẻ bị chặn do lỗi: $(cat "$FAILED" 2>/dev/null || echo 'không')"
    echo "--- log cuối ($LOG)"; tail -n 15 "$LOG" 2>/dev/null || echo "(chưa có log)"
    exit 0 ;;
run|--dry-run) ;;
*) echo "Dùng: $0 [--dry-run|status|pause|resume|unblock]" >&2; exit 2 ;;
esac
DRY=0; [ "${1:-run}" = "--dry-run" ] && DRY=1

# Khóa chống chạy chồng: lượt trước (đang kéo ảnh/chờ khởi động) chưa xong thì lượt này thoát im lặng.
exec 9>"$LOCK"
flock -n 9 || exit 0

rotate_log
[ -f "$PAUSED" ] && { note info "Đang tạm dừng (có file $PAUSED); không làm gì."; exit 0; }
[ -f .env ] || { note warn "Thiếu $HERE/.env; dừng."; exit 0; }
[ -f "$CUR" ] || { note info "Chưa có $CUR: chạy ./deploy.sh deploy bằng tay lần đầu rồi mới bật tự cập nhật."; exit 0; }
cur="$(cat "$CUR")"

head="$(timeout 30 git ls-remote "https://github.com/$REPO.git" "refs/heads/$BRANCH" 2>/dev/null | cut -f1)"
[[ "$head" =~ ^[0-9a-f]{40}$ ]] || { note info "Không hỏi được commit mới nhất của $BRANCH (mạng hoặc GitHub); thử lại lượt sau."; exit 0; }
short="${head:0:9}"

[ "$head" = "$cur" ] && { note info "Đã là bản mới nhất ($short)."; exit 0; }
[ "$head" = "$(cat "$FAILED" 2>/dev/null || true)" ] && { note info "Bản $short đã lỗi khi triển khai trước đó nên không thử lại; chờ commit mới (hoặc ./auto-deploy.sh unblock)."; exit 0; }

# 1) CI phải xanh.
state="$(ci_state "$head" 2>/dev/null)" || { note info "Không đọc được trạng thái CI của $short từ GitHub; thử lại lượt sau."; exit 0; }
case "$state" in
    success) ;;
    pending:*) note info "Bản $short chưa sẵn sàng (${state#pending:}); chờ."; exit 0 ;;
    failed:*)  note warn "CI của bản $short KHÔNG xanh (${state#failed:}); không triển khai. Đang chạy: ${cur:0:9}."; exit 0 ;;
    *)         note info "Trạng thái CI lạ cho $short: $state; không triển khai."; exit 0 ;;
esac

# 2) Ảnh của đúng commit phải có trên ghcr (CI xanh chưa chắc ảnh đã dựng xong).
$SUDO docker manifest inspect "$IMAGE:$head" >/dev/null 2>&1 || { note info "CI của $short xanh nhưng ảnh $IMAGE:$short chưa có trên ghcr; chờ."; exit 0; }

# 3) Máy phải đủ sức: RAM, đĩa, và bản hiện tại đang khỏe (nếu đang hỏng thì lùi về đâu cũng vô nghĩa).
free="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
[ "$free" -ge "$MIN_FREE_MB" ] || { note info "RAM trống ${free} MB < ${MIN_FREE_MB} MB; hoãn để không ảnh hưởng dự án chạy chung."; exit 0; }
disk="$(df -Pm "$HERE" | awk 'NR==2 {print $4}')"
[ "$disk" -ge "$MIN_DISK_MB" ] || { note warn "Đĩa trống ${disk} MB < ${MIN_DISK_MB} MB; hoãn (cần chỗ cho ảnh mới và bản sao lưu)."; exit 0; }
healthy || { note warn "Ứng dụng đang chạy (${cur:0:9}) KHÔNG khỏe; không tự triển khai chồng lên. Cần người xem (./deploy.sh status)."; exit 0; }

if [ "$DRY" = 1 ]; then
    log "[dry-run] sẽ triển khai ${short} (CI xanh, ảnh có sẵn, RAM trống ${free} MB, đĩa trống ${disk} MB) thay cho ${cur:0:9}."
    exit 0
fi

# 4) Triển khai. deploy.sh tự: kiểm RAM, sao lưu DB (dừng nếu sao lưu lỗi), kéo ảnh, chờ khỏe tối đa 3 phút, lùi nếu hỏng.
log "BẮT ĐẦU triển khai ${short} (đang chạy ${cur:0:9})."
snapshot
out="$(mktemp)"; trap 'rm -f "$out"' EXIT
timeout 1800 ./deploy.sh deploy "$head" >"$out" 2>&1 9>&-
rc=$?
sed 's/^/    | /' "$out" | while IFS= read -r l; do log "$l"; done
backup="$(grep -o 'Đã sao lưu: [^ ]*' "$out" | tail -n1 | cut -d' ' -f4)"

if [ "$rc" -eq 0 ] && [ "$(cat "$CUR" 2>/dev/null)" = "$head" ]; then
    rm -f "$FAILED" "$ATTEMPTS"; printf '' >"$NOTE"
    log "XONG: adh đang chạy ${short}. Bản sao lưu trước khi cập nhật: ${backup:-không có (chưa chạy postgres?)}"
    snapshot
    # Dọn ảnh cũ của ứng dụng và của bộ nối Zalo (deploy.sh kéo cả hai khi bật profile zalo-auto), chỉ giữ thẻ đang chạy
    # và thẻ trước (để lùi được); không đụng ảnh khác trên máy.
    keep_prev="$(cat "$PREV" 2>/dev/null || true)"
    for img in "$IMAGE" "$BRIDGE_IMAGE"; do
        for t in $($SUDO docker images --format '{{.Tag}}' "$img" 2>/dev/null); do
            [ "$t" = "$head" ] || [ "$t" = "$keep_prev" ] || $SUDO docker rmi "$img:$t" >/dev/null 2>&1 || true
        done
    done
    notify "đã cập nhật lên ${short}."
    exit 0
fi

# Thất bại: phân biệt lỗi tạm (mạng, thiếu RAM, sao lưu) với bản mới thật sự không chạy được.
n=0; [ "$(cut -d' ' -f1 "$ATTEMPTS" 2>/dev/null)" = "$head" ] && n="$(cut -d' ' -f2 "$ATTEMPTS")"
n=$((n + 1)); echo "$head $n" >"$ATTEMPTS"
now="$(cat "$CUR" 2>/dev/null || true)"
if grep -q 'ứng dụng không khỏe' "$out"; then
    echo "$head" >"$FAILED"   # đã khởi động thử và hỏng: KHÔNG thử lại (migration có thể đã chạy một phần)
    if healthy; then
        msg="bản ${short} KHÔNG khỏe sau khi triển khai; đã lùi về ${now:0:9} và chặn bản này."
    else
        msg="bản ${short} KHÔNG khỏe và lùi về ${now:0:9} cũng chưa khỏe: CẦN NGƯỜI XEM NGAY (./deploy.sh status, logs)."
    fi
    msg="$msg Cơ sở dữ liệu có thể đã được migration đổi trước khi lùi; bản sao lưu trước đó: ${backup:-không có}. Khôi phục: docs/RUNBOOK.md."
    log "THẤT BẠI: $msg"; printf '%s' "$msg" >"$NOTE"; notify "$msg"
elif [ "$n" -ge "$MAX_ATTEMPTS" ]; then
    echo "$head" >"$FAILED"
    msg="triển khai ${short} lỗi $n lần liên tiếp (mã $rc, xem log); ngừng thử bản này. Đang chạy ${now:0:9}."
    log "THẤT BẠI: $msg"; printf '%s' "$msg" >"$NOTE"; notify "$msg"
else
    log "Lỗi tạm khi triển khai ${short} (mã $rc, lần $n/$MAX_ATTEMPTS); đang chạy ${now:0:9}; thử lại lượt cron sau."
fi
exit 1
