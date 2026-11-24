// Đếm ngược tới lần gửi tin kế tiếp: phần tử có id "next-send", data-at là mốc thời gian (mili giây). Chỉ hiển thị, không gửi gì.
(function () {
    var box = document.getElementById('next-send');
    if (!box) {
        return;
    }
    var out = box.querySelector('[data-countdown]');
    var at = parseInt(box.getAttribute('data-at'), 10);
    if (!out || isNaN(at)) {
        return;
    }
    function pad(n) {
        return n < 10 ? '0' + n : String(n);
    }
    function tick() {
        var s = Math.round((at - Date.now()) / 1000);
        if (s <= 0) {
            out.textContent = 'đến giờ, sắp gửi';
            return;
        }
        var h = Math.floor(s / 3600);
        var m = Math.floor((s % 3600) / 60);
        if (h > 0) {
            out.textContent = h + ' giờ ' + pad(m) + ' phút ' + pad(s % 60) + ' giây';
        } else {
            out.textContent = m + ' phút ' + pad(s % 60) + ' giây';
        }
    }
    tick();
    setInterval(tick, 1000);
    // Đến mốc thì tải lại trang một lần sau 20 giây để thấy mốc mới.
    var wait = at - Date.now();
    if (wait > 0 && wait < 3600000 * 12) {
        setTimeout(function () { setTimeout(function () { window.location.reload(); }, 20000); }, wait);
    }
})();
