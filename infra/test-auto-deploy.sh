#!/usr/bin/env bash
# Thử auto-deploy.sh bằng lệnh giả (git, curl, docker, deploy.sh): không cần Docker, mạng hay máy chủ thật.
#   infra/test-auto-deploy.sh
set -uo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/infra"
cp "$SRC/auto-deploy.sh" "$T/infra/"
touch "$T/infra/.env"
export FAKE="$T/fake"; mkdir -p "$FAKE"
SHA1=1111111111111111111111111111111111111111
SHA2=2222222222222222222222222222222222222222

cat >"$T/bin/git" <<'EOF'
#!/usr/bin/env bash
[ "$1" = ls-remote ] || exit 1
[ -f "$FAKE/head" ] && printf '%s\trefs/heads/main\n' "$(cat "$FAKE/head")"
exit 0
EOF
cat >"$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
args="$*"
case "$args" in
  *127.0.0.1*) cat "$FAKE/health" 2>/dev/null || printf 200 ;;
  *actions/runs*) [ -f "$FAKE/ci.json" ] || exit 22; cat "$FAKE/ci.json" ;;
  *) exit 1 ;;
esac
EOF
cat >"$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >>"$FAKE/docker.calls"
case "$1" in
  info) exit 0 ;;
  manifest) [ -f "$FAKE/no-image" ] && exit 1; exit 0 ;;
  images) printf '%s\n' ${FAKE_IMAGES:-} ;;
  ps|stats|rmi) exit 0 ;;
esac
EOF
chmod +x "$T/bin/"*
# deploy.sh giả: hành vi chọn bằng file $FAKE/mode (ok | unhealthy | transient)
cat >"$T/infra/deploy.sh" <<'EOF'
#!/usr/bin/env bash
echo "deploy.sh $*" >>"$FAKE/deploy.calls"
case "$(cat "$FAKE/mode")" in
  ok) echo "Đã sao lưu: /var/backups/adh/adh-1.sql.gz (2000 byte chưa nén)"; echo "Triển khai thẻ $2"
      cat .current-tag >.previous-tag; echo "$2" >.current-tag; echo "XONG" ;;
  unhealthy) echo "Đã sao lưu: /var/backups/adh/adh-2.sql.gz (2000 byte chưa nén)"; echo "Triển khai thẻ $2"
      echo "LỖI: ứng dụng không khỏe sau 3 phút." >&2; exit 1 ;;
  transient) echo "Đã sao lưu: /var/backups/adh/adh-3.sql.gz (2000 byte chưa nén)"; echo "Triển khai thẻ $2"
      echo "Error response from daemon: net/http: TLS handshake timeout" >&2; exit 1 ;;
esac
EOF
chmod +x "$T/infra/deploy.sh"

export PATH="$T/bin:$PATH"
export ADH_AUTO_MIN_DISK_MB=1 ADH_AUTO_MIN_FREE_MB=1
RUN="$T/infra/auto-deploy.sh"
fails=0
ok() { echo "  ok: $1"; }
bad() { echo "  LỖI: $1"; fails=$((fails + 1)); }
expect_log() { grep -q -- "$1" "$T/infra/auto-deploy.log" 2>/dev/null && ok "$2" || { bad "$2 (không thấy '$1' trong log)"; tail -n 5 "$T/infra/auto-deploy.log" 2>/dev/null; }; }
deploy_calls() { if [ -f "$FAKE/deploy.calls" ]; then wc -l <"$FAKE/deploy.calls" | tr -d " "; else echo 0; fi; }
ci() { printf '{"workflow_runs":[{"path":".github/workflows/ci.yml","event":"push","head_branch":"main","run_number":%s,"run_attempt":1,"status":"%s","conclusion":%s}]}' "$1" "$2" "$3" >"$FAKE/ci.json"; }
reset() { rm -f "$T/infra/".auto-* "$T/infra/auto-deploy.log" "$FAKE"/*; echo "$SHA1" >"$T/infra/.current-tag"; echo "$SHA2" >"$FAKE/head"; echo ok >"$FAKE/mode"; ci 1 completed '"success"'; }

echo "1. chưa có .current-tag: không tự chạy lần đầu"
reset; rm "$T/infra/.current-tag"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "không gọi deploy.sh" || bad "đã gọi deploy.sh"
expect_log "Chưa có .current-tag" "ghi lý do"

echo "2. tạm dừng"
reset; "$RUN" pause >/dev/null; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "không gọi deploy.sh" || bad "đã gọi deploy.sh"
"$RUN" resume >/dev/null; [ ! -f "$T/infra/.auto-deploy-paused" ] && ok "resume bỏ dấu" || bad "resume không bỏ dấu"

echo "3. đã là bản mới nhất"
reset; echo "$SHA2" >"$T/infra/.current-tag"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "không gọi deploy.sh" || bad "đã gọi deploy.sh"

echo "4. CI chưa xong / đỏ / chưa có"
reset; ci 1 in_progress null; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "CI đang chạy: chờ" || bad "triển khai khi CI chưa xong"
reset; ci 1 completed '"failure"'; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "CI đỏ: không triển khai" || bad "triển khai khi CI đỏ"
expect_log "KHÔNG xanh" "báo CI đỏ"
reset; echo '{"workflow_runs":[]}' >"$FAKE/ci.json"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "chưa có lần chạy CI: chờ" || bad "triển khai khi chưa có CI"
reset; ci 1 completed '"cancelled"'; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "CI bị hủy: không triển khai" || bad "triển khai khi CI bị hủy"
reset; rm "$FAKE/ci.json"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "API GitHub lỗi: không triển khai" || bad "triển khai khi không đọc được CI"
reset; printf '{"workflow_runs":[{"path":".github/workflows/ci.yml","event":"push","head_branch":"main","run_number":1,"status":"completed","conclusion":"failure"},{"path":".github/workflows/ci.yml","event":"push","head_branch":"main","run_number":2,"status":"completed","conclusion":"success"}]}' >"$FAKE/ci.json"
"$RUN"; [ "$(deploy_calls)" = 1 ] && ok "chạy lại CI xanh (run_number lớn nhất) thì triển khai" || bad "không lấy lần chạy CI mới nhất"
reset; printf '{"workflow_runs":[{"path":".github/workflows/image.yml","event":"push","head_branch":"main","run_number":1,"status":"completed","conclusion":"success"}]}' >"$FAKE/ci.json"
"$RUN"; [ "$(deploy_calls)" = 0 ] && ok "chỉ có workflow image xanh (không phải ci): không triển khai" || bad "nhầm workflow khác là CI"

echo "5. ảnh chưa có trên ghcr"
reset; touch "$FAKE/no-image"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "chờ ảnh" || bad "triển khai khi chưa có ảnh"

echo "6. thiếu RAM / ứng dụng đang hỏng"
reset; ADH_AUTO_MIN_FREE_MB=99999999 "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "hoãn khi thiếu RAM" || bad "triển khai khi thiếu RAM"
reset; printf 502 >"$FAKE/health"; "$RUN"; [ "$(deploy_calls)" = 0 ] && ok "không chồng lên app đang hỏng" || bad "triển khai khi app đang hỏng"

echo "7. khóa chống chạy chồng"
reset; ( exec 8>"$T/infra/.auto-deploy.lock"; flock -n 8; "$RUN"; [ "$(deploy_calls)" = 0 ] ) && ok "lượt đang chạy thì lượt sau thoát" || bad "chạy chồng"

echo "8. dry-run"
reset; "$RUN" --dry-run; [ "$(deploy_calls)" = 0 ] && ok "không gọi deploy.sh" || bad "dry-run vẫn triển khai"
expect_log "dry-run" "báo sẽ làm gì"

echo "9. triển khai thành công"
reset; FAKE_IMAGES="$SHA1 $SHA2 3333" "$RUN"; rc=$?
[ "$rc" = 0 ] && ok "mã thoát 0" || bad "mã thoát $rc"
grep -q "deploy.sh deploy $SHA2" "$FAKE/deploy.calls" && ok "truyền đúng thẻ đã kiểm CI" || bad "sai thẻ"
[ "$(cat "$T/infra/.current-tag")" = "$SHA2" ] && ok ".current-tag mới" || bad ".current-tag sai"
expect_log "adh-1.sql.gz" "log ghi bản sao lưu"
grep -q "rmi ghcr.io/nexagnet/adh-app:3333" "$FAKE/docker.calls" && ok "dọn ảnh cũ" || bad "không dọn ảnh cũ"
! grep -q "rmi ghcr.io/nexagnet/adh-app:$SHA1" "$FAKE/docker.calls" && ok "giữ ảnh của thẻ trước (để lùi)" || bad "xóa nhầm ảnh thẻ trước"
"$RUN"; [ "$(deploy_calls)" = 1 ] && ok "lượt sau không triển khai lại" || bad "triển khai lặp"

echo "10. bản mới không khỏe: lùi, chặn, không thử lại"
reset; echo unhealthy >"$FAKE/mode"; "$RUN"; rc=$?
[ "$rc" = 1 ] && ok "mã thoát 1" || bad "mã thoát $rc"
[ "$(cat "$T/infra/.auto-failed-tag")" = "$SHA2" ] && ok "đánh dấu bản lỗi" || bad "không đánh dấu"
expect_log "adh-2.sql.gz" "log ghi bản sao lưu để khôi phục"
"$RUN"; [ "$(deploy_calls)" = 1 ] && ok "không thử lại bản đã lỗi" || bad "thử lại bản đã lỗi"
echo "$(printf 3333333333333333333333333333333333333333)" >"$FAKE/head"; echo ok >"$FAKE/mode"; "$RUN"
[ "$(deploy_calls)" = 2 ] && ok "commit mới thì được thử" || bad "commit mới không được thử"

echo "11. lỗi tạm: thử lại tối đa 3 lần rồi chặn"
reset; echo transient >"$FAKE/mode"
"$RUN"; "$RUN"; [ ! -f "$T/infra/.auto-failed-tag" ] && ok "2 lần đầu chưa chặn" || bad "chặn quá sớm"
"$RUN"; [ "$(cat "$T/infra/.auto-failed-tag" 2>/dev/null)" = "$SHA2" ] && ok "lần 3 thì chặn" || bad "không chặn sau 3 lần"
[ "$(deploy_calls)" = 3 ] && ok "đúng 3 lần gọi" || bad "số lần gọi $(deploy_calls)"
"$RUN" unblock >/dev/null; echo ok >"$FAKE/mode"; "$RUN"; [ "$(cat "$T/infra/.current-tag")" = "$SHA2" ] && ok "unblock rồi triển khai được" || bad "unblock không có tác dụng"

echo "12. báo người khi có việc"
reset; echo unhealthy >"$FAKE/mode"; printf '#!/usr/bin/env bash\necho "$*" >>"%s/notify.out"\n' "$FAKE" >"$T/bin/n"; chmod +x "$T/bin/n"
ADH_AUTO_NOTIFY_CMD="$T/bin/n" "$RUN"; grep -q "KHÔNG khỏe" "$FAKE/notify.out" 2>/dev/null && ok "gọi lệnh báo" || bad "không gọi lệnh báo"

echo
[ "$fails" = 0 ] && echo "TẤT CẢ ĐẠT" || { echo "$fails kiểm tra lỗi"; exit 1; }
