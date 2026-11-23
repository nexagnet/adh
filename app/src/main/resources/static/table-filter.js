// Bảng danh sách khách. (1) Bấm biểu tượng phễu ở tiêu đề cột thì mở cửa sổ nhỏ để lọc cột đó; bấm ra ngoài hoặc Esc thì đóng.
// (2) Ô chọn và ô ngày tự áp dụng ngay khi đổi; ô gõ chữ/số áp dụng khi bấm Enter hoặc nút "Áp dụng".
// (3) Bảng có thể cuộn ngang: cột đầu (số điện thoại) được ghim, có bóng mờ khi đã cuộn.
(function () {
    var table = document.getElementById('grid');
    var form = document.getElementById('colfilters');
    // Ô chọn tự gửi biểu mẫu khi đổi (khung chọn khu vực cụ thể).
    document.querySelectorAll('select[data-autosubmit]').forEach(function (el) {
        el.addEventListener('change', function () { el.form.submit(); });
    });
    if (!table || !form || !window.FPop) return;

    var apply = document.getElementById('applyfilters');
    if (apply) apply.hidden = true;

    function hasValue(controls) {
        return controls.some(function (el) { return el.value !== ''; });
    }

    // Điện thoại: bảng hiện từng khách thành một thẻ, cần tên cột gắn vào từng ô.
    var heads = Array.prototype.map.call(table.querySelectorAll('thead tr:first-child th'), function (th) {
        return th.querySelector('a.sort span').textContent.trim();
    });
    table.querySelectorAll('tbody tr').forEach(function (tr) {
        var cells = tr.children;
        if (cells.length !== heads.length) return;
        Array.prototype.forEach.call(cells, function (td, i) {
            td.setAttribute('data-label', heads[i]);
            if (td.textContent.trim() === '') td.classList.add('blank');
        });
    });

    table.querySelectorAll('.fbtn').forEach(function (btn) {
        var key = btn.getAttribute('data-col');
        var cell = table.querySelector('tr.filter-row > td[data-col="' + key + '"]');
        if (!cell) return;
        var title = btn.getAttribute('title') || 'Lọc';
        var controls = Array.prototype.slice.call(cell.querySelectorAll('input, select'));
        var active = hasValue(controls);
        btn.hidden = false;
        btn.classList.toggle('active', active);
        btn.setAttribute('aria-expanded', 'false');
        btn.setAttribute('aria-haspopup', 'dialog');

        var panel = document.createElement('div');
        panel.className = 'fpop';
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-label', title);
        panel.hidden = true;
        var head = document.createElement('div');
        head.className = 'fpop-head';
        var h = document.createElement('strong');
        h.textContent = title;
        var x = document.createElement('button');
        x.type = 'button';
        x.className = 'fpop-x';
        x.setAttribute('aria-label', 'Đóng');
        x.textContent = '×';
        x.addEventListener('click', function () { window.FPop.close(true); });
        head.appendChild(h);
        head.appendChild(x);
        var body = document.createElement('div');
        body.className = 'fpop-body';
        while (cell.firstChild) body.appendChild(cell.firstChild);
        panel.appendChild(head);
        panel.appendChild(body);

        var foot = document.createElement('div');
        foot.className = 'fpop-foot';
        var ok = document.createElement('button');
        ok.type = 'submit';
        ok.setAttribute('form', 'colfilters');
        ok.className = 'btn sm';
        ok.textContent = 'Áp dụng';
        foot.appendChild(ok);
        if (active) {
            var clear = document.createElement('button');
            clear.type = 'button';
            clear.className = 'btn secondary sm';
            clear.textContent = 'Bỏ lọc cột này';
            clear.addEventListener('click', function () {
                controls.forEach(function (el) { el.value = ''; });
                form.submit();
            });
            foot.appendChild(clear);
        }
        panel.appendChild(foot);
        document.body.appendChild(panel);

        btn.addEventListener('click', function () { window.FPop.open(btn, panel); });
    });
    table.querySelectorAll('tr.filter-row').forEach(function (tr) { tr.remove(); });

    document.querySelectorAll('select[form="colfilters"], input[type="date"][form="colfilters"]').forEach(function (el) {
        el.addEventListener('change', function () { form.submit(); });
    });

    // Ghim cột đầu: bóng mờ chỉ hiện khi bảng đã cuộn ngang.
    var wrap = table.closest('.table-wrap');
    if (wrap) {
        var sync = function () { wrap.classList.toggle('scrolled', wrap.scrollLeft > 2); };
        wrap.addEventListener('scroll', sync, { passive: true });
        sync();
    }
})();
