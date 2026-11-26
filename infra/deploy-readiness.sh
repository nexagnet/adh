#!/usr/bin/env bash
# Hợp đồng readiness dùng chung cho deploy tay và cron. SHA do ảnh xuất ra, không do .env cung cấp.

adh_revision() { [[ "$1" =~ ^[0-9a-f]{40}$ ]]; }

adh_ready_at() { # cổng loopback, SHA mong đợi
    local reply
    [[ "$1" =~ ^[0-9]{1,5}$ ]] && adh_revision "$2" || return 1
    reply="$(curl -q -s --noproxy '*' --max-time 5 --max-filesize 128 -w $'\n%{http_code}' "http://127.0.0.1:$1/readyz")" || return 1
    [[ "$reply" == "$2"$'\n200' ]]
}
