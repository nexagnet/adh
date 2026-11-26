#!/usr/bin/env bash
# THỬ khôi phục: nạp bản sao lưu mới nhất vào một cơ sở dữ liệu tạm riêng, kiểm tra, rồi xóa nó.
# Không đụng tới dữ liệu đang chạy. Nên chạy định kỳ (ví dụ mỗi tháng): bản sao lưu chưa từng khôi phục thử thì chưa đáng tin.
# Biến môi trường: BACKUP_DIR, PSQL_CMD (lệnh psql, KHÔNG gồm tên cơ sở dữ liệu), AGE_IDENTITY (khóa riêng nếu có mã hóa).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=infra/backup-common.sh
. "$HERE/backup-common.sh"

BACKUP_DIR="$(backup_setting BACKUP_DIR "${BACKUP_DIR:-/var/backups/adh}")"
AGE_IDENTITY="$(backup_setting AGE_IDENTITY "${AGE_IDENTITY:-}")"
PSQL_CMD="$(backup_setting PSQL_CMD "${PSQL_CMD:-}")"
[ -d "$BACKUP_DIR" ] || broken_backup "Không tìm thấy thư mục sao lưu: $BACKUP_DIR"

latest=""
while IFS= read -r -d '' entry; do
    path="${entry#* }"
    name="${path##*/}"
    if [[ ( -d "$path" && "$name" =~ $bundle_name_re ) || ( -f "$path" && "$name" =~ $legacy_name_re ) ]]; then
        latest="$path"
        break
    fi
done < <(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 \( -type d -o -type f \) -printf '%T@ %p\0' | sort -znr)
[ -n "$latest" ] || broken_backup "Không tìm thấy bản sao lưu hoàn tất trong $BACKUP_DIR"
if [ -d "$latest" ]; then
    archive="$latest/${latest##*/}.sql.gz"
    if [ -e "$archive.age" ]; then
        [ ! -e "$archive" ] || broken_backup "Thư mục sao lưu có nhiều tệp dữ liệu."
        archive="$archive.age"
    fi
    [[ -f "$archive" && ! -L "$archive" && -f "$latest/SHA256" && ! -L "$latest/SHA256" ]] \
        || broken_backup "Bản sao lưu thiếu tệp dữ liệu hoặc checksum."
    read -r expected <"$latest/SHA256"
    [[ "$expected" =~ ^[0-9a-f]{64}$ && "$expected" = "$(archive_sha256 "$archive")" ]] \
        || broken_backup "Checksum SHA256 của bản sao lưu không khớp."
else
    archive="$latest"
fi
echo "Thử khôi phục từ: $archive"

if [[ "$archive" == *.age ]]; then
    [ -n "$AGE_IDENTITY" ] || broken_backup "Bản sao lưu đã mã hóa, cần đặt AGE_IDENTITY=<file khóa riêng>."
    reader=(age -d -i "$AGE_IDENTITY" "$archive")
else
    reader=(cat -- "$archive")
fi
# Xác minh khóa và dữ liệu nén trước khi kết nối PostgreSQL.
"${reader[@]}" | gzip -t || broken_backup "Không đọc được bản sao lưu hoặc khóa giải mã sai."

if [ -n "$PSQL_CMD" ]; then
    read -r -a psql_command <<<"$PSQL_CMD"
else
    backup_compose
    psql_command=("${postgres_compose[@]}" psql -U crm)
fi

SCRATCH="crm_restore_test_${RANDOM}_${RANDOM}_${RANDOM}"
created=false
run_sql() { "${psql_command[@]}" -X -w -v ON_ERROR_STOP=1 -q "$@"; }
cleanup() {
    if [ "$created" = true ]; then
        run_sql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH" >/dev/null \
            || { echo "LỖI: không xóa được DB thử $SCRATCH." >&2; return 1; }
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_sql -d postgres -c "CREATE DATABASE $SCRATCH"
created=true
"${reader[@]}" | gzip -dc | run_sql -d "$SCRATCH" >/dev/null

count_rows() {
    local what="$1" sql="$2" got
    got="$(run_sql -d "$SCRATCH" -At -c "$sql")" || return 1
    [[ "$got" =~ ^[0-9]+$ ]] || broken_backup "Kết quả đếm không hợp lệ ($what)."
    echo "  $what: $got"
    [ "${3:-}" != required ] || [ "$got" != 0 ]
}

echo "Kiểm tra dữ liệu đã khôi phục:"
count_rows "số migration đã chạy" "SELECT count(*) FROM flyway_schema_history WHERE success" required
count_rows "bảng trong lược đồ public" "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'" required
# các bảng cốt lõi phải tồn tại (số dòng có thể bằng 0 nếu chưa nhập dữ liệu)
for table in customer shipment contact_basis suppression invitation send_log app_setting; do
    run_sql -d "$SCRATCH" -At -c "SELECT count(*) FROM $table" >/dev/null \
        || { echo "LỖI: thiếu bảng $table sau khi khôi phục" >&2; exit 1; }
done
count_rows "số khách trong bản sao lưu" "SELECT count(*) FROM customer"
count_rows "số dòng danh sách từ chối" "SELECT count(*) FROM suppression"
count_rows "số cầu dao chung đã lưu" "SELECT count(*) FROM app_setting WHERE key LIKE 'zalo.halt.account.%'"
count_rows "số con trỏ inbox đã lưu" "SELECT count(*) FROM app_setting WHERE key LIKE '%zalo.inbox.cursor'"
cleanup
created=false
echo "THỬ KHÔI PHỤC THÀNH CÔNG"
