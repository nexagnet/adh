#!/usr/bin/env bash
# Triển khai adh lên máy chủ DÙNG CHUNG (không đụng Caddy/cổng 80-443 của dự án khác).
#   ./deploy.sh deploy [thẻ]   kéo ảnh ghcr.io/nexagnet/adh-app:<thẻ> (mặc định: commit mới nhất của main) và chạy; tự lùi lại nếu không khỏe
#   ./deploy.sh rollback       quay về thẻ trước đó
#   ./deploy.sh status         trạng thái container, thẻ đang chạy, RAM còn trống
#   ./deploy.sh logs [dịch vụ] xem log (mặc định app)
#   ./deploy.sh backup         sao lưu cơ sở dữ liệu ngay (gọi backup.sh)
# Cần file infra/.env (xem .env.example). Docker cần quyền: nếu user không thuộc nhóm docker thì script tự dùng sudo.
# Tệp compose và profile lấy từ infra/.env (COMPOSE_FILE, COMPOSE_PROFILES, cách nhau bằng dấu hai chấm / dấu phẩy), để cron tự deploy
# giữ nguyên cấu hình nhiều tài khoản Zalo (D23). Không đặt gì thì mặc định docker-compose.shared.yml, không profile nào.
# Biến COMPOSE_FILE / COMPOSE_PROFILES của môi trường shell bị BỎ QUA có chủ ý (cron và chạy tay phải ra cùng một kết quả).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
[ -f .env ] || { echo "LỖI: thiếu $HERE/.env (cp .env.example .env rồi sửa, chmod 600)" >&2; exit 1; }

# Đọc một biến từ .env từng dòng (KHÔNG nạp file như mã); lấy dòng cuối, bỏ dấu nháy và ký tự xuống dòng kiểu Windows.
env_get() { # $1 tên, $2 mặc định
    local v
    v="$(grep -E "^$1=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//" || true)"
    printf '%s' "${v:-$2}"
}
COMPOSE_FILE="$(env_get COMPOSE_FILE docker-compose.shared.yml)"
COMPOSE_PROFILES="$(env_get COMPOSE_PROFILES '')"
export COMPOSE_FILE COMPOSE_PROFILES

# Kiểm tra danh sách tệp: tệp đầu BẮT BUỘC là docker-compose.shared.yml (bản dùng chung, không có Caddy chiếm cổng 80/443 của dự án khác),
# mỗi tệp là tên trơn nằm cạnh script và phải tồn tại. Sai thì dừng NGAY, chưa đổi gì.
compose_args=()
check_compose_files() {
    local f first=1 IFS=:
    for f in $COMPOSE_FILE; do
        [[ "$f" =~ ^[A-Za-z0-9._-]+\.ya?ml$ ]] || { echo "LỖI: COMPOSE_FILE trong .env có tên tệp không hợp lệ: '$f'" >&2; return 1; }
        if [ "$first" = 1 ] && [ "$f" != docker-compose.shared.yml ]; then
            echo "LỖI: COMPOSE_FILE trong .env phải bắt đầu bằng docker-compose.shared.yml (máy chủ dùng chung), đang là '$f'." >&2; return 1
        fi
        first=0
        [ -f "$HERE/$f" ] || { echo "LỖI: COMPOSE_FILE trong .env nêu tệp '$f' nhưng $HERE/$f không có. Chép tệp lên máy chủ trước." >&2; return 1; }
        compose_args+=(-f "$f")
    done
    [ "$first" = 0 ] || { echo "LỖI: COMPOSE_FILE trong .env rỗng" >&2; return 1; }
    IFS=,
    for f in $COMPOSE_PROFILES; do
        [[ "$f" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "LỖI: COMPOSE_PROFILES trong .env có tên profile không hợp lệ: '$f'" >&2; return 1; }
        compose_args+=(--profile "$f")
    done
}
check_compose_files || exit 1

if docker info >/dev/null 2>&1; then SUDO=""; else SUDO="sudo "; fi
export DOCKER_SUDO="$SUDO"
# sudo xóa biến môi trường, nên truyền ADH_TAG qua env để thẻ ảnh không bị mất. Tệp và profile đi bằng cờ -f / --profile (sudo-an toàn);
# hai biến COMPOSE_* được đặt rỗng để biến của môi trường không chen vào.
dc() { $SUDO env "ADH_TAG=${ADH_TAG:-}" COMPOSE_FILE= COMPOSE_PROFILES= docker compose "${compose_args[@]}" --env-file .env "$@"; }

HOST_PORT="$(grep -E '^ADH_HOST_PORT=' .env | cut -d= -f2- || true)"; HOST_PORT="${HOST_PORT:-8081}"
MIN_FREE_MB="${MIN_FREE_MB:-250}"
CUR=.current-tag; PREV=.previous-tag

healthy() {
    # Ứng dụng không có điểm kiểm tra riêng: coi là khỏe nếu trả mã HTTP dưới 500 (trang đăng nhập/chuyển hướng cũng được).
    local code; code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$HOST_PORT/" || true)"
    [[ "$code" =~ ^[1-4][0-9][0-9]$ ]]
}

wait_healthy() {
    for _ in $(seq 1 36); do healthy && return 0; sleep 5; done
    return 1
}

has_service() { # dịch vụ có trong cấu hình đang dùng (đã tính tệp compose và profile bật trong .env)
    dc config --services 2>/dev/null | grep -qx "$1"
}

up_with() { # $1 = thẻ. Chỉ kéo ảnh và tạo lại dịch vụ của dự án compose "adh"; không bao giờ down/rm/--remove-orphans.
    ADH_TAG="$1" dc pull app
    # Cầu nối Zalo (D16) và cầu nối tài khoản thứ hai (D23) chỉ được kéo khi profile của chúng đang bật trong .env.
    local s
    for s in zalo-bridge zalo-bridge-2; do
        if has_service "$s"; then ADH_TAG="$1" dc pull "$s"; fi
    done
    ADH_TAG="$1" dc up -d
}

latest_main_tag() {
    git -C "$HERE" ls-remote https://github.com/nexagnet/adh.git refs/heads/main | cut -f1
}

deployment_flag_enabled() { # .env và môi trường có thể khác nhau; khóa nếu một trong hai đang bật.
    [[ "${!1:-false}" != false || "$(env_get "$1" false)" != false ]]
}

shared_group_rollback_locked() {
    deployment_flag_enabled APP_ZALO_SHARED_GROUPS_ENABLED || deployment_flag_enabled ADH_ZALO_SHARED_GROUP_HISTORY
}

cmd="${1:-status}"; shift || true
if [[ "$cmd" == deploy || "$cmd" == rollback ]] && deployment_flag_enabled APP_ZALO_RESTORE_FENCE; then
    echo "LỖI: khóa phục hồi Zalo đang bật. Dừng cập nhật ảnh và quay lui trong khi đối chiếu dữ liệu." >&2
    exit 1
fi
case "$cmd" in
deploy)
    tag="${1:-$(latest_main_tag)}"
    [ -n "$tag" ] || { echo "LỖI: không lấy được thẻ; truyền thẻ làm tham số" >&2; exit 1; }
    free="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
    echo "RAM còn trống: ${free} MB (cần tối thiểu ${MIN_FREE_MB} MB; đặt MIN_FREE_MB để đổi)"
    if [ "$free" -lt "$MIN_FREE_MB" ]; then
        echo "LỖI: RAM còn quá ít, dừng để không ảnh hưởng dự án chạy chung. Không có gì bị đổi." >&2; exit 1
    fi
    echo "Cấu hình compose: COMPOSE_FILE=$COMPOSE_FILE COMPOSE_PROFILES=${COMPOSE_PROFILES:-(không)}"
    # Kiểm cấu hình TRƯỚC khi sao lưu hay đổi gì (thiếu khóa bắt buộc, tệp YAML hỏng...): lỗi thì dừng, container giữ nguyên.
    ADH_TAG="$tag" dc config -q || { echo "LỖI: cấu hình compose không hợp lệ (xem thông báo trên). Không có gì bị đổi." >&2; exit 1; }
    prev=""; [ -f "$CUR" ] && prev="$(cat "$CUR")"
    if [ -n "$($SUDO docker ps -q --filter label=com.docker.compose.project=adh --filter label=com.docker.compose.service=postgres)" ]; then
        echo "Sao lưu trước khi cập nhật..."; ./backup.sh
    fi
    echo "Triển khai thẻ $tag"
    up_with "$tag"
    if wait_healthy; then
        [ -n "$prev" ] && [ "$prev" != "$tag" ] && echo "$prev" > "$PREV"
        echo "$tag" > "$CUR"
        echo "XONG: adh chạy thẻ $tag tại 127.0.0.1:$HOST_PORT"
    else
        echo "LỖI: ứng dụng không khỏe sau 3 phút." >&2
        dc logs --tail 40 app >&2 || true
        if shared_group_rollback_locked; then
            echo "LỖI: khóa lùi ảnh vì nhóm dùng chung. Giữ nguyên bản để kiểm tra và sửa tiến." >&2
        elif [ -n "$prev" ] && [ "$prev" != "$tag" ]; then
            echo "Lùi về $prev..." >&2; up_with "$prev"
            wait_healthy && echo "Đã lùi về $prev" >&2 || echo "CẢNH BÁO: lùi về $prev cũng chưa khỏe, kiểm tra thủ công" >&2
        fi
        exit 1
    fi ;;
rollback)
    if shared_group_rollback_locked; then
        echo "LỖI: khóa lùi ảnh vì nhóm dùng chung. Xem quy trình lùi ảnh và khôi phục trong RUNBOOK." >&2
        exit 1
    fi
    [ -f "$PREV" ] || { echo "LỖI: chưa có thẻ trước đó" >&2; exit 1; }
    tag="$(cat "$PREV")"; cur="$(cat "$CUR" 2>/dev/null || true)"
    up_with "$tag"
    wait_healthy || { echo "LỖI: thẻ $tag không khỏe" >&2; exit 1; }
    echo "$tag" > "$CUR"; [ -n "$cur" ] && echo "$cur" > "$PREV"
    echo "Đã quay về $tag" ;;
status)
    echo "Thẻ đang chạy: $(cat "$CUR" 2>/dev/null || echo 'chưa có')   thẻ trước: $(cat "$PREV" 2>/dev/null || echo 'chưa có')"
    ADH_TAG="$(cat "$CUR" 2>/dev/null || echo none)" dc ps
    healthy && echo "HTTP 127.0.0.1:$HOST_PORT: khỏe" || echo "HTTP 127.0.0.1:$HOST_PORT: không phản hồi"
    free -m | sed -n '1,3p' ;;
logs)
    ADH_TAG="$(cat "$CUR" 2>/dev/null || echo none)" dc logs --tail 200 -f "${1:-app}" ;;
backup)
    ./backup.sh ;;
*)
    echo "Dùng: $0 {deploy [thẻ]|rollback|status|logs [dịch vụ]|backup}" >&2; exit 2 ;;
esac
