// Bảng danh sách khách. (1) Biểu tượng phễu ở tiêu đề mỗi cột bật/tắt ô lọc của cột đó; cột đang có điều kiện lọc tự mở sẵn.
// (2) Ô chọn và ô ngày tự áp dụng ngay khi đổi; ô gõ chữ/số áp dụng khi bấm Enter hoặc nút "Lọc".
(function () {
    var table = document.getElementById('grid');
    if (!table) return;
    var apply = document.getElementById('applyfilters');

    function refresh() {
        var open = table.querySelectorAll('tr.filter-row > td.open').length > 0;
        table.classList.toggle('has-open', open);
        if (apply) apply.hidden = !open;
    }

    function hasValue(cell) {
        return Array.prototype.some.call(cell.querySelectorAll('input, select'), function (el) { return el.value !== ''; });
    }

    // Điện thoại: bảng hiện từng khách thành một thẻ, cần tên cột gắn vào từng ô.
    var labels = {};
    table.querySelectorAll('thead th').forEach(function (th) {
        var key = th.querySelector('.fbtn').getAttribute('data-col');
        labels[key] = th.querySelector('a.sort span').textContent.trim().toLowerCase();
    });
    var heads = Array.prototype.map.call(table.querySelectorAll('thead tr:first-child th'), function (th) {
        return th.querySelector('a.sort span').textContent.trim();
    });
    table.querySelectorAll('tr.filter-row > td').forEach(function (td) {
        td.setAttribute('data-label', labels[td.getAttribute('data-col')] || '');
    });
    table.querySelectorAll('tbody tr').forEach(function (tr) {
        var cells = tr.children;
        if (cells.length !== heads.length) return;
        Array.prototype.forEach.call(cells, function (td, i) {
            td.setAttribute('data-label', heads[i]);
            if (td.textContent.trim() === '') td.classList.add('blank');
        });
    });

    table.classList.add('js-filters');
    table.querySelectorAll('.fbtn').forEach(function (btn) {
        var cell = table.querySelector('tr.filter-row > td[data-col="' + btn.getAttribute('data-col') + '"]');
        if (!cell) return;
        btn.hidden = false;
        var active = hasValue(cell);
        btn.classList.toggle('active', active);
        cell.classList.toggle('open', active);
        btn.setAttribute('aria-expanded', active ? 'true' : 'false');
        btn.addEventListener('click', function () {
            var open = !cell.classList.contains('open');
            cell.classList.toggle('open', open);
            btn.setAttribute('aria-expanded', open ? 'true' : 'false');
            refresh();
            if (open) {
                var first = cell.querySelector('input, select');
                if (first) first.focus();
            }
        });
    });
    refresh();

    document.querySelectorAll('select[form="colfilters"], input[type="date"][form="colfilters"]').forEach(function (el) {
        el.addEventListener('change', function () {
            el.form.submit();
        });
    });
})();
