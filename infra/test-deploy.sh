#!/usr/bin/env bash
# Thử deploy.sh, backup.sh và restore-test.sh bằng lệnh giả (docker, curl, sudo): không cần Docker, mạng hay máy chủ thật.
# Kiểm tra: đọc COMPOSE_FILE / COMPOSE_PROFILES từ .env (nhiều tài khoản Zalo, D23), mặc định không đổi, chỉ đụng dịch vụ của adh.
#   infra/test-deploy.sh
set -uo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/infra"
cp "$SRC/deploy.sh" "$SRC/deploy-readiness.sh" "$SRC/backup.sh" "$SRC/backup-common.sh" "$SRC/restore-test.sh" "$T/infra/"
for f in docker-compose.shared.yml docker-compose.zalo-2.yml docker-compose.prod.yml; do : >"$T/infra/$f"; done
export FAKE="$T/fake"; mkdir -p "$FAKE"
TAG=3333333333333333333333333333333333333333
PREVIOUS=4444444444444444444444444444444444444444
SCHEMA=55555555555555555555555555555555
export TAG PREVIOUS SCHEMA

# docker giả: ghi mọi lệnh; `config --services` mô phỏng profile (dịch vụ có profile chỉ hiện khi profile bật).
cat >"$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >>"$FAKE/docker.calls"
case "$1" in
  info) exit 0 ;;
  ps) [ -f "$FAKE/pg" ] && echo abc123; exit 0 ;;
  image)
    [[ "$*" != *"$(cat "$FAKE/legacy" 2>/dev/null || echo missing)" ]] || exit 1
    printf '%s 1\n' "${*: -1}" | sed 's/^[^:]*://'
    exit 0 ;;
esac
if [ "$1" = compose ]; then
  args=" $* "
  case "$args" in
    *" config -q "*) [ -f "$FAKE/config-fail" ] && { echo "required variable missing" >&2; exit 1; }; exit 0 ;;
    *" config --services "*)
      echo app; echo postgres
      [[ "$args" == *"--profile zalo-auto"* ]] && echo zalo-bridge
      [[ "$args" == *"docker-compose.zalo-2.yml"* && "$args" == *"--profile zalo-bridge-2"* ]] && echo zalo-bridge-2
      exit 0 ;;
    *" up -d "*)
      printf '%s' "$ADH_TAG" >"$FAKE/running"
      [ ! -f "$FAKE/migration" ] || printf '%032d' 6 >"$FAKE/schema"
      exit 0 ;;
    *" exec "*"flyway_schema_history"*) cat "$FAKE/schema" 2>/dev/null || printf '%s' "$SCHEMA"; exit 0 ;;
    *" exec "*" pg_dump "*) head -c 3000 /dev/zero | tr '\0' x; exit 0 ;;
    *" exec "*" psql "*) printf '1\n'; exit 0 ;;
  esac
fi
exit 0
EOF
cat >"$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
running="$(cat "$FAKE/running")"
if [ "$running" = "$TAG" ] && [ -f "$FAKE/unhealthy" ]; then
    cat "$FAKE/unhealthy"
else
    printf '%s\n200' "$running"
fi
EOF
cat >"$T/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$T/bin/"*
export PATH="$T/bin:$PATH"
export MIN_FREE_MB=1 BACKUP_DIR="$T/backups"

fails=0
ok() { echo "  ok: $1"; }
bad() { echo "  LỖI: $1"; fails=$((fails + 1)); }
calls() { cat "$FAKE/docker.calls" 2>/dev/null; }
has() { calls | grep -qF -- "$1"; }
reset() { rm -f "$FAKE"/* "$T/infra/".current-tag "$T/infra/".previous-tag "$T/infra/".previous-schema; rm -rf "$BACKUP_DIR"; printf 'DB_PASSWORD=x\nAPP_ADMIN_PASSWORD=y\n%s' "${1:-}" >"$T/infra/.env"; }
deploy() { (cd "$T/infra" && ./deploy.sh deploy "$TAG" >"$FAKE/out" 2>&1); }
expect_has() { has "$1" && ok "$2" || { bad "$2 (không thấy '$1')"; calls | sed 's/^/      /'; }; }
expect_not() { has "$1" && { bad "$2 (lại thấy '$1')"; calls | sed 's/^/      /'; } || ok "$2"; }

echo "1. .env không đặt COMPOSE_*: y như cũ (chỉ docker-compose.shared.yml, không profile, kéo mỗi app)"
reset; deploy && ok "deploy thành công" || { bad "deploy lỗi"; cat "$FAKE/out"; }
expect_has "compose -f docker-compose.shared.yml --env-file .env up -d" "tạo lại bằng một tệp shared"
expect_not "--profile" "không có profile"
expect_not "pull zalo-bridge" "không kéo cầu nối"
expect_has "pull app" "kéo ảnh app"
[ "$(cat "$T/infra/.current-tag")" = "$TAG" ] && ok "ghi .current-tag" || bad "không ghi .current-tag"

echo "2. biến COMPOSE_* của môi trường shell bị bỏ qua"
reset; (cd "$T/infra" && COMPOSE_FILE=docker-compose.prod.yml COMPOSE_PROFILES=lung-tung ./deploy.sh deploy "$TAG" >"$FAKE/out" 2>&1) && ok "deploy thành công" || bad "deploy lỗi"
expect_not "docker-compose.prod.yml" "không dùng tệp prod từ môi trường"
expect_not "lung-tung" "không dùng profile từ môi trường"

echo "3. .env bật tài khoản 2 cùng máy: hai tệp, hai profile, kéo cả hai cầu nối"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto,zalo-bridge-2
'
deploy && ok "deploy thành công" || { bad "deploy lỗi"; cat "$FAKE/out"; }
expect_has "-f docker-compose.shared.yml -f docker-compose.zalo-2.yml --profile zalo-auto --profile zalo-bridge-2 --env-file .env up -d" "up -d có đủ tệp và profile"
expect_has "pull app" "kéo app"
expect_has "pull zalo-bridge" "kéo cầu nối 1"
expect_has "pull zalo-bridge-2" "kéo cầu nối 2"

echo "4. cầu nối thứ hai Ở MÁY KHÁC (không profile zalo-bridge-2): không kéo cầu nối 2"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto
'
deploy && ok "deploy thành công" || bad "deploy lỗi"
expect_has "pull zalo-bridge" "kéo cầu nối 1"
expect_not "pull zalo-bridge-2" "không kéo cầu nối 2"

echo "5. giá trị có nháy và CRLF"
reset "$(printf 'COMPOSE_FILE="docker-compose.shared.yml:docker-compose.zalo-2.yml"\r\nCOMPOSE_PROFILES='"'"'zalo-auto,zalo-bridge-2'"'"'\r\n')"
deploy && ok "deploy thành công" || { bad "deploy lỗi"; cat "$FAKE/out"; }
expect_has "-f docker-compose.zalo-2.yml --profile zalo-auto --profile zalo-bridge-2" "đã bỏ nháy và CR"

echo "6. bị từ chối trước khi đổi gì"
reset 'COMPOSE_FILE=docker-compose.prod.yml
'
deploy && bad "chấp nhận tệp đầu không phải shared (Caddy chiếm 80/443!)" || ok "tệp đầu phải là docker-compose.shared.yml"
expect_not " up " "không chạy up"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.khong-co.yml
'
deploy && bad "chấp nhận tệp không tồn tại" || ok "tệp không tồn tại bị từ chối"
expect_not " up " "không chạy up"
reset 'COMPOSE_FILE=docker-compose.shared.yml:../etc/passwd
'
deploy && bad "chấp nhận đường dẫn lạ" || ok "đường dẫn lạ bị từ chối"
reset 'COMPOSE_PROFILES=zalo-auto;rm -rf /
'
deploy && bad "chấp nhận profile lạ" || ok "tên profile lạ bị từ chối"
expect_not " up " "không chạy up"

echo "7. cấu hình compose hỏng: dừng trước khi sao lưu hay đổi gì"
reset; touch "$FAKE/config-fail" "$FAKE/pg"
deploy && bad "vẫn chạy khi cấu hình hỏng" || ok "dừng"
expect_not " up " "không chạy up"
expect_not "pg_dump" "không sao lưu"
[ ! -f "$T/infra/.current-tag" ] && ok "không ghi .current-tag" || bad "đã ghi .current-tag"

echo "8. sao lưu trước khi cập nhật dùng đủ các tệp compose"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto,zalo-bridge-2
'
touch "$FAKE/pg"; deploy && ok "deploy thành công" || { bad "deploy lỗi"; cat "$FAKE/out"; }
expect_has "-f $T/infra/docker-compose.shared.yml -f $T/infra/docker-compose.zalo-2.yml --profile zalo-auto --profile zalo-bridge-2 --env-file $T/infra/.env exec -T postgres pg_dump" "pg_dump qua hai tệp và profile tương ứng"
expect_not "shared.yml:" "không còn tên tệp ghép bằng dấu hai chấm"
compgen -G "$BACKUP_DIR/adh-*/adh-*.sql.gz" >/dev/null && ok "có file sao lưu" || bad "không có file sao lưu"

echo "8b. đã đặt cờ multi thì phải cấu hình đường tới cầu nối thứ hai"
reset 'APP_ZALO_MULTI_ENABLED=true
'
deploy && bad "bỏ qua cờ multi khi thiếu tệp bổ sung" || ok "yêu cầu tệp Compose cho tài khoản 2"
expect_not "pull app" "chưa kéo ảnh khi cấu hình multi chưa đủ"
reset 'APP_ZALO_MULTI_ENABLED=true
COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto
'
deploy && bad "đoán địa chỉ khi thiếu cầu nối 2" || ok "yêu cầu địa chỉ riêng khi chạy hai máy"
expect_not "pull app" "chưa kéo ảnh khi thiếu địa chỉ cầu nối"

echo "9. tự lùi và rollback dùng cùng cấu hình"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto,zalo-bridge-2
'
echo "$PREVIOUS" >"$T/infra/.current-tag"; touch "$FAKE/pg"; deploy
: >"$FAKE/docker.calls"; (cd "$T/infra" && ./deploy.sh rollback >"$FAKE/out" 2>&1) && ok "rollback thành công" || { bad "rollback lỗi"; cat "$FAKE/out"; }
expect_has "-f docker-compose.zalo-2.yml --profile zalo-auto --profile zalo-bridge-2 --env-file .env up -d" "rollback giữ tệp và profile"
expect_has "pull zalo-bridge-2" "rollback kéo cầu nối 2"

echo "9b. readiness và lùi ảnh cần đúng SHA, DB khỏe, và schema không đổi"
for response in 302 404 503 $'\n200' "$PREVIOUS"$'\n200'; do
    reset; echo "$PREVIOUS" >"$T/infra/.current-tag"; touch "$FAKE/pg"
    printf '%s' "$response" >"$FAKE/unhealthy"
    deploy && bad "chấp nhận HTTP sai hoặc SHA thiếu/sai" || ok "từ chối HTTP sai hoặc SHA thiếu/sai"
    [ "$(cat "$FAKE/running")" = "$PREVIOUS" ] && ok "lùi về đúng SHA trước đó" || bad "không lùi về đúng SHA trước đó"
    [ "$(cat "$T/infra/.current-tag")" = "$PREVIOUS" ] && ok "thẻ hiện tại vẫn là bản vừa xác minh" || bad "thẻ hiện tại đổi dù bản mới lỗi"
done
reset; echo "$PREVIOUS" >"$T/infra/.current-tag"; touch "$FAKE/pg" "$FAKE/migration"
printf 503 >"$FAKE/unhealthy"
deploy && bad "chấp nhận bản mới lỗi" || ok "từ chối bản mới lỗi"
[ "$(cat "$FAKE/running")" = "$TAG" ] && ok "không lùi ảnh sau migration" || bad "lùi ảnh dù schema đã đổi"
reset; echo "$PREVIOUS" >"$T/infra/.current-tag"; touch "$FAKE/pg"
printf 503 >"$FAKE/unhealthy"; echo "$PREVIOUS" >"$FAKE/legacy"
deploy && bad "chấp nhận bản mới lỗi" || ok "từ chối bản mới lỗi"
[ "$(cat "$FAKE/running")" = "$TAG" ] && ok "không lùi ảnh cũ thiếu xác minh readiness" || bad "lùi sang ảnh cũ thiếu xác minh readiness"

echo "10. không bao giờ dừng/xóa thứ gì (chỉ pull, up -d, config, ps, exec, logs)"
bad_cmd=0
for c in " down" " rm " " stop" " kill" "--remove-orphans" " prune" " rmi" " volume rm" " restart"; do
    grep -qF -- "$c" "$FAKE/docker.calls" && { bad "có lệnh '$c'"; bad_cmd=1; }
done
[ "$bad_cmd" = 0 ] && ok "không có lệnh dừng/xóa/dọn nào"

echo "11. backup.sh và restore-test.sh: một tệp thì lệnh y như cũ"
reset; touch "$FAKE/pg"
(cd "$T/infra" && COMPOSE_FILE=docker-compose.shared.yml ./backup.sh >"$FAKE/out" 2>&1) && ok "backup.sh chạy" || { bad "backup.sh lỗi"; cat "$FAKE/out"; }
expect_has "compose -f $T/infra/docker-compose.shared.yml --env-file $T/infra/.env exec -T postgres pg_dump -w -U crm -d crm" "lệnh pg_dump dùng đúng cấu hình một tệp"
(cd "$T/infra" && COMPOSE_FILE=docker-compose.shared.yml ./restore-test.sh >"$FAKE/out" 2>&1); has "-f $T/infra/docker-compose.shared.yml --env-file $T/infra/.env exec -T postgres psql" && ok "restore-test.sh dùng cờ -f đúng" || bad "restore-test.sh sai cờ -f"
reset 'COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
'
touch "$FAKE/pg"; (cd "$T/infra" && ./backup.sh >/dev/null 2>&1)
(cd "$T/infra" && ./restore-test.sh >"$FAKE/out" 2>&1); has "-f $T/infra/docker-compose.shared.yml -f $T/infra/docker-compose.zalo-2.yml --env-file $T/infra/.env exec -T postgres psql" && ok "restore-test.sh tách hai tệp" || bad "restore-test.sh không tách tệp"

echo
[ "$fails" = 0 ] && echo "TẤT CẢ ĐẠT" || { echo "$fails kiểm tra lỗi"; exit 1; }
