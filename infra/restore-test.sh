#!/usr/bin/env bash
# THỬ khôi phục: nạp bản sao lưu mới nhất vào một cơ sở dữ liệu tạm (crm_restore_test), kiểm tra, rồi xóa nó.
# Không đụng tới dữ liệu đang chạy. Nên chạy định kỳ (ví dụ mỗi tháng): bản sao lưu chưa từng khôi phục thử thì chưa đáng tin.
# Biến môi trường: BACKUP_DIR, PSQL_CMD (lệnh psql, KHÔNG gồm tên cơ sở dữ liệu), AGE_IDENTITY (khóa riêng nếu có mã hóa).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/.env" ] && set -a && . "$HERE/.env" && set +a

BACKUP_DIR="${BACKUP_DIR:-/var/backups/adh}"
# COMPOSE_FILE có thể là DANH SÁCH cách nhau bằng dấu hai chấm (D23: docker-compose.shared.yml:docker-compose.zalo-2.yml); cờ -f chỉ nhận một tệp,
# nên tách ra thành nhiều cờ -f. Một tệp thì y như trước.
compose_f=""
IFS=: read -ra _cf <<<"${COMPOSE_FILE:-docker-compose.prod.yml}"
for _f in "${_cf[@]}"; do compose_f="$compose_f -f $HERE/$_f"; done
PSQL_CMD="${PSQL_CMD:-${DOCKER_SUDO:-}docker compose$compose_f --env-file $HERE/.env exec -T postgres psql -U crm}"
SCRATCH="crm_restore_test"

latest="$(find "$BACKUP_DIR" -maxdepth 1 -name 'adh-*.sql.gz*' -printf '%T@ %p\n' | sort -n | tail -1 | cut -d' ' -f2-)"
if [ -z "$latest" ]; then
    echo "LỖI: không tìm thấy bản sao lưu nào trong $BACKUP_DIR" >&2
    exit 1
fi
echo "Thử khôi phục từ: $latest"

if [[ "$latest" == *.age ]]; then
    [ -n "${AGE_IDENTITY:-}" ] || { echo "LỖI: bản sao lưu đã mã hóa, cần đặt AGE_IDENTITY=<file khóa riêng>" >&2; exit 1; }
    reader=(age -d -i "$AGE_IDENTITY" "$latest")
else
    reader=(cat "$latest")
fi

# shellcheck disable=SC2086
run_sql() { $PSQL_CMD -v ON_ERROR_STOP=1 -q "$@"; }
cleanup() { run_sql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

run_sql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH"
run_sql -d postgres -c "CREATE DATABASE $SCRATCH"
"${reader[@]}" | gzip -dc | run_sql -d "$SCRATCH" >/dev/null

check() {
    local what="$1" sql="$2" got
    got="$(run_sql -d "$SCRATCH" -At -c "$sql")"
    echo "  $what: $got"
    [ "$got" != "0" ] && [ -n "$got" ]
}

echo "Kiểm tra dữ liệu đã khôi phục:"
check "số migration đã chạy" "SELECT count(*) FROM flyway_schema_history WHERE success"
check "bảng trong lược đồ public" "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
# các bảng cốt lõi phải tồn tại (số dòng có thể bằng 0 nếu chưa nhập dữ liệu)
for table in customer shipment contact_basis suppression invitation send_log; do
    run_sql -d "$SCRATCH" -At -c "SELECT count(*) FROM $table" >/dev/null \
        || { echo "LỖI: thiếu bảng $table sau khi khôi phục" >&2; exit 1; }
done
echo "  số khách trong bản sao lưu: $(run_sql -d "$SCRATCH" -At -c 'SELECT count(*) FROM customer')"
echo "THỬ KHÔI PHỤC THÀNH CÔNG"
