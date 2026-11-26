#!/usr/bin/env bash
# Thử sao lưu và khôi phục bằng lệnh giả, không gọi Docker hay dữ liệu thật.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf -- "$T"' EXIT
mkdir -p "$T/infra" "$T/bin"
cp "$SRC/backup.sh" "$SRC/restore-test.sh" "$T/infra/"
if [ -f "$SRC/backup-common.sh" ]; then cp "$SRC/backup-common.sh" "$T/infra/"; fi
cd "$T/infra"
export PATH="$T/bin:$PATH" BACKUP_DIR="$T/backups with spaces" LOG="$T/sql.calls"
export PG_DUMP_CMD=dump PSQL_CMD=psql AGE_IDENTITY=valid

cat >"$T/bin/dump" <<'EOF'
#!/usr/bin/env bash
head -c 3000 /dev/zero | tr '\0' x
exit "${FAIL_DUMP:-0}"
EOF
cat >"$T/bin/date" <<'EOF'
#!/usr/bin/env bash
echo 20261126-030000
EOF
cat >"$T/bin/age" <<'EOF'
#!/usr/bin/env bash
set -eu
if [ "$1" = -d ]; then
    [ "$3" = valid ] || exit 5
    sed '1d' "$4"
else
    { echo fake-age; cat "$5"; } >"$4"
    exit "${FAIL_AGE:-0}"
fi
EOF
cat >"$T/bin/psql" <<'EOF'
#!/usr/bin/env bash
set -eu
echo "$*" >>"$LOG"
if [[ " $* " == *' -c '* ]]; then
    [[ " $* " != *' SELECT '* ]] || echo 1
else
    cat >/dev/null
    exit "${FAIL_SQL:-0}"
fi
EOF
chmod +x "$T/bin/"*

fail() { echo "LỖI: $*" >&2; exit 1; }
expect_failure() { if "$@" >"$T/error.log" 2>&1; then fail "đáng lẽ thất bại: $*"; fi; }

echo "1. Đọc .env như dữ liệu, bảo toàn đường dẫn có khoảng trắng và xuất hai lượt cùng giây"
printf '%s\n' 'UNRELATED=$(touch "$LOG")' "BACKUP_DIR='$BACKUP_DIR'" >.env
bash ./backup.sh >"$T/backup-1"
bash ./backup.sh >"$T/backup-2"
[ ! -e "$LOG" ] || fail ".env đã được thi hành"
shopt -s nullglob
bundles=("$BACKUP_DIR"/adh-20261126-030000-*)
[ "${#bundles[@]}" = 2 ] || fail "hai lượt cùng giây ghi đè"
for bundle in "${bundles[@]}"; do
    [ -d "$bundle" ] && [ -f "$bundle/SHA256" ] || fail "thiếu checksum"
    gzip -t "$bundle/${bundle##*/}.sql.gz"
done

echo "2. Lỗi dump hoặc age viết một phần không để lại bản hoàn tất hay tệp tạm"
expect_failure env FAIL_DUMP=5 bash ./backup.sh
expect_failure env FAIL_AGE=5 BACKUP_AGE_RECIPIENT=test bash ./backup.sh
after_failure=("$BACKUP_DIR"/* "$BACKUP_DIR"/.adh-*)
[ "${#after_failure[@]}" = 2 ] || fail "lộ tệp đang viết"

echo "3. Hai lần khôi phục dùng hai DB riêng và chỉ xóa DB đã tạo"
bash ./restore-test.sh >"$T/restore-1" &
first=$!
bash ./restore-test.sh >"$T/restore-2" &
second=$!
wait "$first"
wait "$second"
mapfile -t database_names < <(sed -nE 's/.* CREATE DATABASE (crm_restore_test_[[:alnum:]_]+).*/\1/p' "$LOG" | sort -u)
[ "${#database_names[@]}" = 2 ] || fail "khôi phục dùng chung DB"
[ "$(grep -c 'DROP DATABASE' "$LOG")" = 2 ] || fail "xóa ngoài DB được tạo"
for database in "${database_names[@]}"; do
    grep -q "DROP DATABASE IF EXISTS $database" "$LOG"
done

echo "4. File dở bị bỏ qua. Dữ liệu hỏng hoặc sai khóa bị từ chối trước khi gọi DB"
touch "$BACKUP_DIR/adh-20990101-000000.sql.gz.partial"
touch "$BACKUP_DIR"/.adh-20990101-000000-fixture.partial
bash ./restore-test.sh >"$T/restore-3"
BACKUP_AGE_RECIPIENT='test' bash ./backup.sh >"$T/backup-encrypted"
encrypted=("$BACKUP_DIR"/*/*.sql.gz.age)
[ "${#encrypted[@]}" = 1 ]
touch "${encrypted[0]%/*}"
: >"$LOG"
expect_failure env AGE_IDENTITY=incorrect bash ./restore-test.sh
[ ! -s "$LOG" ] || fail "đã gọi DB dù sai khóa"
bash ./restore-test.sh >"$T/restore-encrypted"
: >"$LOG"
printf x >>"${encrypted[0]}"
expect_failure bash ./restore-test.sh
grep -qi checksum "$T/error.log" || fail "không phát hiện checksum sai"
[ ! -s "$LOG" ] || fail "đã gọi DB dù checksum sai"

echo "5. Lỗi nhập chỉ xóa DB vừa tạo và vẫn đọc được bản sao lưu phẳng cũ"
legacy="$T/legacy"
mkdir "$legacy"
printf '%s\n' "BACKUP_DIR='$legacy'" >.env
dump | gzip -c >"$legacy/adh-20261125-030000.sql.gz"
touch "$legacy/adh-20990101-000000.sql.gz.partial"
: >"$LOG"
expect_failure env FAIL_SQL=5 bash ./restore-test.sh
[ "$(grep -c 'CREATE DATABASE' "$LOG")" = 1 ]
[ "$(grep -c 'DROP DATABASE' "$LOG")" = 1 ]
bash ./restore-test.sh >"$T/restore-legacy"
grep -q 'THỬ KHÔI PHỤC THÀNH CÔNG' "$T/restore-legacy"
echo "Kiểm thử sao lưu và khôi phục đạt."
