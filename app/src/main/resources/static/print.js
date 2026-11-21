// Nút "In trang này" trên trang in giấy giới thiệu (CSP không cho viết lệnh trực tiếp trong HTML).
document.addEventListener('DOMContentLoaded', function () {
    var b = document.getElementById('print-btn');
    if (b) {
        b.addEventListener('click', function () { window.print(); });
    }
});
