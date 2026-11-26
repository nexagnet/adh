#!/usr/bin/env bash
# Dùng chung cho backup.sh và restore-test.sh.

# Chỉ đọc các biến do script yêu cầu. Không mở rộng biến hay chạy lệnh trong .env.
backup_setting() {
    local key="$1" value="$2" line raw
    local assignment="^[[:blank:]]*(export[[:blank:]]+)?${key}[[:blank:]]*=[[:blank:]]*(.*)$"
    local single="^'([^']*)'[[:blank:]]*(#.*)?$"
    local double='^"([^"]*)"[[:blank:]]*(#.*)?$'
    if [ -f "$HERE/.env" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            line="${line%$'\r'}"
            [[ "$line" =~ $assignment ]] || continue
            raw="${BASH_REMATCH[2]}"
            if [[ "$raw" =~ $single || "$raw" =~ $double ]]; then
                value="${BASH_REMATCH[1]}"
            else
                value="$(printf '%s' "$raw" | sed -E 's/[[:blank:]]+#.*$//; s/[[:blank:]]+$//')"
            fi
        done <"$HERE/.env"
    fi
    printf '%s' "$value"
}

backup_compose() {
    local files profiles prefix file profile
    local -a compose_files compose_profiles
    files="$(backup_setting COMPOSE_FILE "${COMPOSE_FILE:-}")"
    profiles="$(backup_setting COMPOSE_PROFILES "${COMPOSE_PROFILES:-}")"
    prefix="$(backup_setting DOCKER_SUDO "${DOCKER_SUDO:-}")"
    if [ ! -f "$HERE/.env" ]; then
        echo "LỖI: thiếu $HERE/.env." >&2
        return 1
    fi
    IFS=: read -r -a compose_files <<<"${files:-docker-compose.prod.yml}"
    IFS=, read -r -a compose_profiles <<<"$profiles"
    case "$prefix" in
        "") postgres_compose=(docker compose) ;;
        sudo|"sudo ") postgres_compose=(sudo docker compose) ;;
        *) broken_backup "DOCKER_SUDO chỉ nhận sudo hoặc để trống." ;;
    esac
    for file in "${compose_files[@]}"; do
        if [[ ! "$file" =~ ^[A-Za-z0-9._-]+\.ya?ml$ ]] || [ ! -f "$HERE/$file" ]; then
            echo 'LỖI: COMPOSE_FILE nêu tệp không hợp lệ hoặc không tồn tại.' >&2
            return 1
        fi
        postgres_compose+=(-f "$HERE/$file")
    done
    for profile in "${compose_profiles[@]}"; do
        if [[ ! "$profile" =~ ^[A-Za-z0-9._-]+$ ]]; then
            echo 'LỖI: COMPOSE_PROFILES chứa tên profile không hợp lệ.' >&2
            return 1
        fi
        postgres_compose+=(--profile "$profile")
    done
    postgres_compose+=(--env-file "$HERE/.env" exec -T postgres)
}

# Được dùng bởi backup.sh và restore-test.sh.
# shellcheck disable=SC2034
bundle_name_re='^adh-[0-9]{8}-[0-9]{6}-[[:alnum:]]{8}$'
# shellcheck disable=SC2034
legacy_name_re='^adh-[0-9]{8}-[0-9]{6}\.sql\.gz(\.age)?$'

archive_sha256() {
    local digest
    digest="$(sha256sum <"$1")" || return 1
    printf '%s\n' "${digest%% *}"
}

broken_backup() {
    printf 'LỖI: %s\n' "$1" >&2
    exit 1
}
