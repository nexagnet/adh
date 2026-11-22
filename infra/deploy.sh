#!/usr/bin/env bash
# Triển khai adh lên máy chủ DÙNG CHUNG (không đụng Caddy/cổng 80-443 của dự án khác).
#   ./deploy.sh deploy [thẻ]   kéo ảnh ghcr.io/nexagnet/adh-app:<thẻ> (mặc định: commit mới nhất của main) và chạy; tự lùi lại nếu không khỏe
#   ./deploy.sh rollback       quay về thẻ trước đó
#   ./deploy.sh status         trạng thái container, thẻ đang chạy, RAM còn trống
#   ./deploy.sh logs [dịch vụ] xem log (mặc định app)
#   ./deploy.sh backup         sao lưu cơ sở dữ liệu ngay (gọi backup.sh)
# Cần file infra/.env (xem .env.example). Docker cần quyền: nếu user không thuộc nhóm docker thì script tự dùng sudo.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
export COMPOSE_FILE=docker-compose.shared.yml
[ -f .env ] || { echo "LỖI: thiếu $HERE/.env (cp .env.example .env rồi sửa, chmod 600)" >&2; exit 1; }

if docker info >/dev/null 2>&1; then SUDO=""; else SUDO="sudo "; fi
export DOCKER_SUDO="$SUDO"
# sudo xóa biến môi trường, nên truyền ADH_TAG qua env để thẻ ảnh không bị mất.
dc() { $SUDO env "ADH_TAG=${ADH_TAG:-}" docker compose -f "$COMPOSE_FILE" --env-file .env "$@"; }

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

bridge_on() { # bộ nối Zalo (D16) chỉ chạy khi .env bật profile zalo-auto
    grep -E '^COMPOSE_PROFILES=' .env | tail -n1 | grep -q 'zalo-auto'
}

up_with() { # $1 = thẻ
    ADH_TAG="$1" dc pull app
    if bridge_on; then ADH_TAG="$1" dc pull zalo-bridge; fi
    ADH_TAG="$1" dc up -d
}

latest_main_tag() {
    git -C "$HERE" ls-remote https://github.com/nexagnet/adh.git refs/heads/main | cut -f1
}

cmd="${1:-status}"; shift || true
case "$cmd" in
deploy)
    tag="${1:-$(latest_main_tag)}"
    [ -n "$tag" ] || { echo "LỖI: không lấy được thẻ; truyền thẻ làm tham số" >&2; exit 1; }
    free="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
    echo "RAM còn trống: ${free} MB (cần tối thiểu ${MIN_FREE_MB} MB; đặt MIN_FREE_MB để đổi)"
    if [ "$free" -lt "$MIN_FREE_MB" ]; then
        echo "LỖI: RAM còn quá ít, dừng để không ảnh hưởng dự án chạy chung. Không có gì bị đổi." >&2; exit 1
    fi
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
        if [ -n "$prev" ] && [ "$prev" != "$tag" ]; then
            echo "Lùi về $prev..." >&2; up_with "$prev"
            wait_healthy && echo "Đã lùi về $prev" >&2 || echo "CẢNH BÁO: lùi về $prev cũng chưa khỏe, kiểm tra thủ công" >&2
        fi
        exit 1
    fi ;;
rollback)
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
