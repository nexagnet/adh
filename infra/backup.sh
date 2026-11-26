#!/usr/bin/env bash
# Sao lưu cơ sở dữ liệu: nén, (tùy chọn) mã hóa bằng age, giữ KEEP_DAYS ngày, (tùy chọn) chép ra ngoài máy chủ.
# Chạy hằng ngày bằng cron, ví dụ:  17 3 * * * /đường/dẫn/infra/backup.sh >> /var/log/adh-backup.log 2>&1
# Biến môi trường: BACKUP_DIR, KEEP_DAYS, BACKUP_AGE_RECIPIENT, BACKUP_REMOTE_CMD, PG_DUMP_CMD (xem .env.example).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=infra/backup-common.sh
. "$HERE/backup-common.sh"

BACKUP_DIR="$(backup_setting BACKUP_DIR "${BACKUP_DIR:-/var/backups/adh}")"
KEEP_DAYS="$(backup_setting KEEP_DAYS "${KEEP_DAYS:-14}")"
BACKUP_AGE_RECIPIENT="$(backup_setting BACKUP_AGE_RECIPIENT "${BACKUP_AGE_RECIPIENT:-}")"
BACKUP_REMOTE_CMD="$(backup_setting BACKUP_REMOTE_CMD "${BACKUP_REMOTE_CMD:-}")"
PG_DUMP_CMD="$(backup_setting PG_DUMP_CMD "${PG_DUMP_CMD:-}")"
[[ -n "$BACKUP_DIR" && "$KEEP_DAYS" =~ ^[0-9]+$ ]] || broken_backup "BACKUP_DIR và KEEP_DAYS không hợp lệ."
if [ -n "$PG_DUMP_CMD" ]; then
    read -r -a dump <<<"$PG_DUMP_CMD"
else
    backup_compose
    dump=("${postgres_compose[@]}" pg_dump -w -U crm -d crm)
fi

umask 077
mkdir -p "$BACKUP_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
stage="$(mktemp -d "$BACKUP_DIR/.adh-$stamp-XXXXXXXX")"
name="${stage##*/.}"
bundle="$BACKUP_DIR/$name"
out="$name.sql.gz"
cleanup() {
    if [ -d "$stage" ]; then
        rm -f -- "$stage/$out" "$stage/$out.age" "$stage/SHA256"
        rmdir -- "$stage"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# pipefail: nếu pg_dump lỗi thì cả lệnh lỗi, không để lại file giả.
"${dump[@]}" | gzip -9 >"$stage/$out"
size="$(gzip -dc -- "$stage/$out" | wc -c)"
if [ "$size" -lt 1000 ]; then
    echo "LỖI: bản sao lưu quá nhỏ ($size byte), có thể pg_dump không chạy đúng." >&2
    exit 1
fi

archive="$out"
if [ -n "$BACKUP_AGE_RECIPIENT" ]; then
    age -r "$BACKUP_AGE_RECIPIENT" -o "$stage/$out.age" "$stage/$out"
    [[ -s "$stage/$out.age" ]] || broken_backup "age không tạo dữ liệu."
    rm -- "$stage/$out"
    archive="$out.age"
fi
checksum="$(archive_sha256 "$stage/$archive")"
printf '%s\n' "$checksum" >"$stage/SHA256"
chmod 600 "$stage/$archive" "$stage/SHA256"
mv -T -- "$stage" "$bundle"
final="$bundle/$archive"

# Chỉ loại bỏ bản sao lưu đã hoàn tất. Mỗi thư mục có tên riêng, không đụng tệp đang ghi.
while IFS= read -r -d '' old; do
    old_name="${old##*/}"
    if [[ -d "$old" && "$old_name" =~ $bundle_name_re ]]; then
        rm -f -- "$old/$old_name.sql.gz" "$old/$old_name.sql.gz.age" "$old/SHA256"
        if ! rmdir -- "$old" 2>/dev/null && [ -d "$old" ]; then
            echo "Không xóa được thư mục sao lưu cũ: $old" >&2
        fi
    elif [[ -f "$old" && "$old_name" =~ $legacy_name_re ]]; then
        rm -f -- "$old"
    fi
done < <(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 \( -type d -o -type f \) -mtime +"$KEEP_DAYS" -print0)

if [ -n "$BACKUP_REMOTE_CMD" ]; then
    read -r -a remote <<<"$BACKUP_REMOTE_CMD"
    "${remote[@]}" "$final"
fi
echo "Đã sao lưu: $final ($size byte chưa nén)"
