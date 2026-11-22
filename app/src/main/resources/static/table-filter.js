// Ô chọn và ô ngày trong hàng lọc của bảng tự áp dụng ngay khi đổi; ô gõ chữ/số áp dụng khi bấm Enter hoặc nút "Lọc".
document.querySelectorAll('select[form="colfilters"], input[type="date"][form="colfilters"]').forEach(function (el) {
    el.addEventListener('change', function () {
        el.form.submit();
    });
});
