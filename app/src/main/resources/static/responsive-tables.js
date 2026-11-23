/* Điện thoại: gắn tên cột vào từng ô để CSS hiện bảng dạng thẻ (xem .stack-m trong app.css).
   Bảng có lọc/sắp xếp (table.grid) và bảng ít hơn 4 cột (số liệu gọn) giữ nguyên dạng bảng. */
(function () {
    document.querySelectorAll('table:not(.grid)').forEach(function (t) {
        var head = null;
        t.querySelectorAll('tr').forEach(function (r) {
            if (!head && r.querySelector('th')) head = r;
        });
        if (!head || head.querySelector('th[colspan]') || head.children.length < 4) return;
        var labels = Array.prototype.map.call(head.children, function (th) { return th.textContent.trim(); });
        var rows = 0;
        t.querySelectorAll('tr').forEach(function (r) {
            if (r === head || r.querySelector('th')) return;
            var cells = r.children;
            if (cells.length !== labels.length) return;
            rows++;
            Array.prototype.forEach.call(cells, function (c, i) {
                if (labels[i]) c.setAttribute('data-label', labels[i]);
            });
        });
        if (rows) t.classList.add('stack-m');
    });
})();
