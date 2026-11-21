// Hiện giá trị mẫu (dòng dữ liệu đầu tiên) của cột đang chọn ở màn "Chọn cột".
document.querySelectorAll('.maprow').forEach(function (row) {
    var sel = row.querySelector('select');
    var out = row.querySelector('.sample');
    function show() {
        var opt = sel.options[sel.selectedIndex];
        out.textContent = (opt && opt.getAttribute('data-sample')) || '—';
    }
    sel.addEventListener('change', show);
    show();
});
