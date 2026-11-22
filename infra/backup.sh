#!/usr/bin/env bash
# Sao lưu cơ sở dữ liệu: nén, (tùy chọn) mã hóa bằng age, giữ KEEP_DAYS ngày, (tùy chọn) chép ra ngoài máy chủ.
# Chạy hằng ngày bằng cron, ví dụ:  17 3 * * * /đường/dẫn/infra/backup.sh >> /var/log/adh-backup.log 2>&1
# Biến môi trường: BACKUP_DIR, KEEP_DAYS, BACKUP_AGE_RECIPIENT, BACKUP_REMOTE_CMD, PG_DUMP_CMD (xem .env.example).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/.env" ] && set -a && . "$HERE/.env" && set +a

BACKUP_DIR="${BACKUP_DIR:-/var/backups/adh}"
KEEP_DAYS="${KEEP_DAYS:-14}"
PG_DUMP_CMD="${PG_DUMP_CMD:-${DOCKER_SUDO:-}docker compose -f $HERE/${COMPOSE_FILE:-docker-compose.prod.yml} --env-file $HERE/.env exec -T postgres pg_dump -U crm -d crm}"

umask 077
mkdir -p "$BACKUP_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/adh-$stamp.sql.gz"
tmp="$out.partial"
trap 'rm -f "$tmp"' EXIT

# pipefail: nếu pg_dump lỗi thì cả lệnh lỗi, không để lại file giả.
# shellcheck disable=SC2086
$PG_DUMP_CMD | gzip -9 > "$tmp"
gzip -t "$tmp"
size="$(gzip -dc "$tmp" | wc -c)"
if [ "$size" -lt 1000 ]; then
    echo "LỖI: bản sao lưu quá nhỏ ($size byte), có thể pg_dump không chạy đúng." >&2
    exit 1
fi

if [ -n "${BACKUP_AGE_RECIPIENT:-}" ]; then
    age -r "$BACKUP_AGE_RECIPIENT" -o "$out.age" "$tmp"
    rm -f "$tmp"
    final="$out.age"
else
    mv "$tmp" "$out"
    final="$out"
fi
chmod 600 "$final"

# giữ lại KEEP_DAYS ngày gần nhất
find "$BACKUP_DIR" -maxdepth 1 -name 'adh-*.sql.gz*' -mtime +"$KEEP_DAYS" -delete

if [ -n "${BACKUP_REMOTE_CMD:-}" ]; then
    # shellcheck disable=SC2086
    $BACKUP_REMOTE_CMD "$final"
fi
echo "Đã sao lưu: $final ($size byte chưa nén)"
