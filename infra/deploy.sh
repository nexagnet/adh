#!/usr/bin/env bash
# Triển khai adh lên máy chủ DÙNG CHUNG (không đụng Caddy/cổng 80-443 của dự án khác).
#   ./deploy.sh deploy [SHA]   kéo ảnh ghcr.io/nexagnet/adh-app:<SHA> (mặc định: commit mới nhất của main) và chạy; tự lùi khi schema còn tương thích
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
# shellcheck source=infra/deploy-readiness.sh
source "$HERE/deploy-readiness.sh"

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

check_multi_route() {
    [ "$(env_get APP_ZALO_MULTI_ENABLED false)" = true ] || return 0
    if [[ ":$COMPOSE_FILE:" != *":docker-compose.zalo-2.yml:"* ]]; then
        echo "LỖI: APP_ZALO_MULTI_ENABLED=true cần thêm docker-compose.zalo-2.yml vào COMPOSE_FILE." >&2
        return 1
    fi
    if [[ ",$COMPOSE_PROFILES," != *",zalo-bridge-2,"* ]] && [ -z "$(env_get ZALO_BRIDGE_2_URL '')" ]; then
        echo "LỖI: cần ZALO_BRIDGE_2_URL nếu cầu nối tài khoản 2 chạy ở máy khác." >&2
        return 1
    fi
}

if docker info >/dev/null 2>&1; then SUDO=""; else SUDO="sudo "; fi
export DOCKER_SUDO="$SUDO"
# sudo xóa biến môi trường, nên truyền ADH_TAG qua env để thẻ ảnh không bị mất. Tệp và profile đi bằng cờ -f / --profile (sudo-an toàn);
# hai biến COMPOSE_* được đặt rỗng để biến của môi trường không chen vào.
dc() { $SUDO env "ADH_TAG=${ADH_TAG:-}" "ADH_HOST_PORT=$HOST_PORT" COMPOSE_FILE= COMPOSE_PROFILES= docker compose "${compose_args[@]}" --env-file .env "$@"; }

HOST_PORT="$(env_get ADH_HOST_PORT 8081)"
MIN_FREE_MB="${MIN_FREE_MB:-250}"
CUR=.current-tag; PREV=.previous-tag; PREV_SCHEMA=.previous-schema
APP_IMAGE=ghcr.io/nexagnet/adh-app

wait_healthy() {
    local deadline=$((SECONDS + 175))
    for _ in $(seq 1 36); do
        [ "$SECONDS" -lt "$deadline" ] || break
        adh_ready_at "$HOST_PORT" "$1" && return 0
        [ "$SECONDS" -lt "$deadline" ] && sleep 5
    done
    return 1
}

has_service() { # dịch vụ có trong cấu hình đang dùng (đã tính tệp compose và profile bật trong .env)
    dc config --services 2>/dev/null | grep -qx "$1"
}

prepare_images() { # $1 = thẻ. Xác minh metadata có từ lúc dựng ảnh, trước khi tạo lại bất kỳ dịch vụ nào.
    adh_revision "$1" || return 1
    ADH_TAG="$1" dc pull app || return 1
    local labels s
    labels="$($SUDO docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "vn.shop.crm.readiness"}}' "$APP_IMAGE:$1")" || return 1
    if [ "$labels" != "$1 1" ]; then
        echo "LỖI: ảnh app $1 không có đúng SHA và hợp đồng readiness. Giữ cấu hình hiện tại." >&2
        return 1
    fi
    # Cầu nối Zalo (D16) và cầu nối tài khoản thứ hai (D23) chỉ được kéo khi profile của chúng đang bật trong .env.
    for s in zalo-bridge zalo-bridge-2; do
        if has_service "$s"; then ADH_TAG="$1" dc pull "$s" || return 1; fi
    done
}

up_with() { # Chỉ tạo lại dịch vụ dự án adh, giữ volume và không dừng dịch vụ dự án khác.
    ADH_TAG="$1" dc up -d
}

schema_fingerprint() {
    local value
    value="$(dc exec -T postgres psql -X -v ON_ERROR_STOP=1 -U crm -d crm -Atq -c \
        "SELECT md5(COALESCE(jsonb_agg(jsonb_build_array(installed_rank, version, type, script, checksum, success) ORDER BY installed_rank)::text, '[]')) FROM public.flyway_schema_history" 2>/dev/null)" || return 1
    [[ "$value" =~ ^[0-9a-f]{32}$ ]] || return 1
    printf '%s' "$value"
}

schema_unchanged() {
    local actual
    [[ "$1" =~ ^[0-9a-f]{32}$ ]] && actual="$(schema_fingerprint)" && [ "$actual" = "$1" ]
}

latest_main_tag() {
    git -C "$HERE" ls-remote https://github.com/nexagnet/adh.git refs/heads/main | cut -f1
}

cmd="${1:-status}"; shift || true
case "$cmd" in
deploy)
    check_multi_route || exit 1
    tag="${1:-$(latest_main_tag)}"
    adh_revision "$tag" || { echo "LỖI: cần SHA commit đầy đủ 40 ký tự làm thẻ ảnh" >&2; exit 1; }
    free="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
    echo "RAM còn trống: ${free} MB (cần tối thiểu ${MIN_FREE_MB} MB; đặt MIN_FREE_MB để đổi)"
    if [ "$free" -lt "$MIN_FREE_MB" ]; then
        echo "LỖI: RAM còn quá ít, dừng để không ảnh hưởng dự án chạy chung. Không có gì bị đổi." >&2; exit 1
    fi
    echo "Cấu hình compose: COMPOSE_FILE=$COMPOSE_FILE COMPOSE_PROFILES=${COMPOSE_PROFILES:-(không)}"
    # Kiểm cấu hình TRƯỚC khi sao lưu hay đổi gì (thiếu khóa bắt buộc, tệp YAML hỏng...): lỗi thì dừng, container giữ nguyên.
    ADH_TAG="$tag" dc config -q || { echo "LỖI: cấu hình compose không hợp lệ (xem thông báo trên). Không có gì bị đổi." >&2; exit 1; }
    prev="$(cat "$CUR" 2>/dev/null || true)"
    prepare_images "$tag" || exit 1
    previous_schema=""
    if [ -n "$($SUDO docker ps -q --filter label=com.docker.compose.project=adh --filter label=com.docker.compose.service=postgres)" ]; then
        previous_schema="$(schema_fingerprint || true)"
        echo "Sao lưu trước khi cập nhật..."; ./backup.sh
    fi
    echo "Triển khai thẻ $tag"
    if up_with "$tag" && wait_healthy "$tag"; then
        if adh_revision "$prev" && [ "$prev" != "$tag" ]; then
            printf '%s %s\n' "$prev" "$previous_schema" > "$PREV_SCHEMA"
            echo "$prev" > "$PREV"
        fi
        echo "$tag" > "$CUR"
        echo "XONG: adh chạy thẻ $tag tại 127.0.0.1:$HOST_PORT"
    else
        echo "LỖI: ứng dụng không khỏe hoặc readiness không trả đúng SHA $tag." >&2
        dc logs --tail 40 app >&2 || true
        if adh_revision "$prev" && [ "$prev" != "$tag" ]; then
            if schema_unchanged "$previous_schema" && prepare_images "$prev" && schema_unchanged "$previous_schema"; then
                echo "Lùi về $prev..." >&2
                if up_with "$prev" && wait_healthy "$prev"; then
                    echo "Đã lùi về $prev" >&2
                else
                    echo "CẢNH BÁO: lùi về $prev cũng chưa khỏe, kiểm tra thủ công." >&2
                fi
            else
                echo "LỖI: chưa xác minh ảnh hoặc schema lùi. Giữ bản sao lưu, xử lý theo runbook." >&2
            fi
        fi
        exit 1
    fi ;;
rollback)
    [ -f "$PREV" ] || { echo "LỖI: chưa có thẻ trước đó" >&2; exit 1; }
    tag="$(cat "$PREV")"; cur="$(cat "$CUR" 2>/dev/null || true)"
    saved_tag="" saved_schema=""
    if [ -f "$PREV_SCHEMA" ]; then read -r saved_tag saved_schema < "$PREV_SCHEMA" || exit 1; fi
    if [ "$saved_tag" != "$tag" ] || ! schema_unchanged "$saved_schema"; then
        echo "LỖI: schema không khớp bản trước hoặc chưa được xác minh. Khôi phục theo runbook trước khi lùi ảnh." >&2
        exit 1
    fi
    prepare_images "$tag" && schema_unchanged "$saved_schema" && up_with "$tag" || exit 1
    wait_healthy "$tag" || { echo "LỖI: thẻ $tag không khỏe" >&2; exit 1; }
    echo "$tag" > "$CUR"
    if adh_revision "$cur"; then
        printf '%s %s\n' "$cur" "$saved_schema" > "$PREV_SCHEMA"
        echo "$cur" > "$PREV"
    fi
    echo "Đã quay về $tag" ;;
status)
    echo "Thẻ đang chạy: $(cat "$CUR" 2>/dev/null || echo 'chưa có')   thẻ trước: $(cat "$PREV" 2>/dev/null || echo 'chưa có')"
    ADH_TAG="$(cat "$CUR" 2>/dev/null || echo none)" dc ps
    adh_ready_at "$HOST_PORT" "$(cat "$CUR" 2>/dev/null || true)" \
        && echo "Readiness 127.0.0.1:$HOST_PORT: khỏe, đúng SHA" \
        || echo "Readiness 127.0.0.1:$HOST_PORT: chưa khỏe hoặc chưa xác minh được SHA"
    free -m | sed -n '1,3p' ;;
logs)
    ADH_TAG="$(cat "$CUR" 2>/dev/null || echo none)" dc logs --tail 200 -f "${1:-app}" ;;
backup)
    ./backup.sh ;;
*)
    echo "Dùng: $0 {deploy [thẻ]|rollback|status|logs [dịch vụ]|backup}" >&2; exit 2 ;;
esac
