// Nút "Sao chép tin": chép nội dung ô văn bản (id lấy từ data-copy-target) vào bộ nhớ tạm để dán vào Zalo.
document.querySelectorAll('button[data-copy-target]').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var box = document.getElementById(btn.getAttribute('data-copy-target'));
        if (!box) {
            return;
        }
        box.focus();
        box.select();
        var done = function () {
            btn.textContent = 'Đã sao chép';
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(box.value).then(done, function () {
                document.execCommand('copy');
                done();
            });
        } else {
            document.execCommand('copy');
            done();
        }
    });
});
