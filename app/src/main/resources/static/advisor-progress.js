const advisorForm = document.getElementById('advisor-request');
advisorForm?.addEventListener('submit', () => {
    const button = advisorForm.querySelector('button');
    button.disabled = true;
    button.textContent = 'Đang gửi yêu cầu…';
});

const advisorProcessing = document.getElementById('advisor-processing');
if (advisorProcessing) {
    const progress = document.getElementById('advisor-progress');
    const check = async () => {
        try {
            const response = await fetch(advisorProcessing.dataset.status, {
                cache: 'no-store', headers: { Accept: 'application/json' },
                signal: AbortSignal.timeout(8000)
            });
            if (!response.ok || response.redirected) {
                throw new Error('Không đọc được trạng thái');
            }
            const { running } = await response.json();
            if (running === false) {
                window.location.replace(advisorProcessing.dataset.result);
                return;
            }
            progress.textContent = 'AI đang xử lý. Bạn có thể rời trang và quay lại để xem kết quả.';
        } catch {
            progress.textContent = 'Web chưa lấy được trạng thái. Sẽ thử kiểm tra lại, bạn không cần bấm nút lần nữa.';
        }
        window.setTimeout(check, 3000);
    };
    window.setTimeout(check, 1500);
}
