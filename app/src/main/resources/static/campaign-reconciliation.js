(() => {
    const zonedDate = new Intl.DateTimeFormat('vi-VN', {
        timeZone: 'Asia/Ho_Chi_Minh',
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
    });

    document.querySelectorAll('[data-reconciliation-account]').forEach((account) => {
        const form = account.querySelector('form');
        const button = form.querySelector('button');
        form.addEventListener('submit', () => {
            button.disabled = true;
            button.textContent = 'Đang gửi yêu cầu…';
        });
        if (account.dataset.running !== 'true') return;

        const summary = account.querySelector('[data-progress-summary]');
        const meter = account.querySelector('[data-progress-meter]');
        const message = account.querySelector('[data-progress-message]');
        const latest = account.querySelector('[data-progress-latest]');
        const next = account.querySelector('[data-progress-next]');

        const poll = async () => {
            try {
                const response = await fetch(account.dataset.status, {
                    cache: 'no-store', headers: { Accept: 'application/json' },
                    signal: AbortSignal.timeout(8000)
                });
                if (response.redirected || response.status === 401 || response.status === 403) {
                    message.textContent = 'Hãy đăng nhập lại để xem tiến độ đối chiếu.';
                    return;
                }
                if (!response.ok) throw new Error('Không đọc được tiến độ');

                const job = await response.json();
                if (job.running === false) {
                    window.location.reload();
                    return;
                }
                if (job.running !== true) throw new Error('Tiến độ không hợp lệ');

                const details = [`${job.processed} / ${job.total} lời mời đã xử lý`];
                if (job.failed > 0) details.push(`${job.failed} lỗi đọc`);
                if (job.skipped > 0) details.push(`${job.skipped} bỏ qua`);
                const progress = details.join(' · ');
                if (summary && summary.textContent !== progress) summary.textContent = progress;
                if (meter && job.total > 0) {
                    meter.max = job.total;
                    meter.value = job.processed;
                }
                const description = job.message || 'Đang đọc trạng thái từ Zalo…';
                if (message.textContent !== description) message.textContent = description;
                if (job.latestReadAt) {
                    const readAt = `Đọc Zalo thành công gần nhất · ${zonedDate.format(new Date(job.latestReadAt))}`;
                    if (latest.textContent !== readAt) latest.textContent = readAt;
                }
                if (next) {
                    const checkAgain = job.nextReadAt ? `Lần đọc tiếp theo dự kiến · ${zonedDate.format(new Date(job.nextReadAt))}` : '';
                    if (next.textContent !== checkAgain) next.textContent = checkAgain;
                }
            } catch {
                const unavailable = 'Chưa lấy được tiến độ. Trang sẽ tự kiểm tra lại.';
                if (message.textContent !== unavailable) message.textContent = unavailable;
            }
            window.setTimeout(poll, 5000);
        };
        window.setTimeout(poll, 1500);
    });
})();
