// Lọc theo cột cho các bảng danh sách (bảng có thuộc tính data-filter). Lọc ngay trên máy, không gọi lại máy chủ.
// Mỗi cột có biểu tượng phễu ở tiêu đề: bấm thì mở cửa sổ nhỏ (cột ít giá trị thì chọn từ danh sách, còn lại thì gõ chữ);
// bấm ra ngoài hoặc Esc thì đóng. Có nút "Xóa tất cả bộ lọc". Điện thoại (không thấy tiêu đề cột): một nút "Lọc" mở khung gồm mọi cột.
// Bảng có data-sticky: cột đầu (số điện thoại, tên, mã) được ghim khi cuộn ngang.
(function () {
    var SVG = '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false"><path d="M1.5 2.5h13l-5 5.8v4.4l-3 1.8V8.3z" fill="currentColor"/></svg><span class="fdot" aria-hidden="true"></span>';

    var seq = 0;

    function norm(s) {
        return s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'd').toLowerCase().replace(/\s+/g, ' ').trim();
    }
    function cellText(td) { return td.textContent.replace(/\s+/g, ' ').trim(); }
    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    function pinShadow(table) {
        var wrap = table.closest('.table-wrap');
        if (!wrap || !table.hasAttribute('data-sticky')) return;
        var sync = function () { wrap.classList.toggle('scrolled', wrap.scrollLeft > 2); };
        wrap.addEventListener('scroll', sync, { passive: true });
        sync();
    }

    function setup(table) {
        var head = null;
        table.querySelectorAll('tr').forEach(function (r) { if (!head && r.querySelector('th')) head = r; });
        if (!head) return;
        var ths = Array.prototype.slice.call(head.children);
        var rows = [];
        table.querySelectorAll('tr').forEach(function (r) {
            if (r === head || r.querySelector('th') || r.closest('table') !== table) return;
            if (r.classList.contains('editrow')) {
                if (rows.length) rows[rows.length - 1].extra.push(r);
                return;
            }
            if (r.children.length !== ths.length) return;
            rows.push({ tr: r, extra: [], text: Array.prototype.map.call(r.children, cellText) });
        });
        if (rows.length < 2 || !window.FPop) return;

        var cols = [];
        ths.forEach(function (th, i) {
            var label = th.textContent.replace(/\s+/g, ' ').trim();
            if (!label || label === 'Thao tác' || th.hasAttribute('colspan') || th.hasAttribute('data-nofilter')) return;
            var distinct = {};
            var n = 0;
            rows.forEach(function (r) {
                var v = r.text[i];
                if (v !== '' && !(v in distinct)) { distinct[v] = true; n++; }
            });
            if (n === 0) return;
            var values = Object.keys(distinct).sort(function (a, b) { return a.localeCompare(b, 'vi'); });
            cols.push({ i: i, th: th, label: label, mode: n >= 2 && n <= 10 ? 'select' : 'text', values: values, value: '' });
        });
        if (!cols.length) return;

        // Thanh trên bảng: trạng thái, nút xóa tất cả, và (điện thoại) nút "Lọc".
        var anchor = table.closest('.table-wrap') || table;
        var bar = el('div', 'lf-bar');
        var openAll = el('button', 'btn secondary sm lf-all', 'Lọc');
        openAll.type = 'button';
        openAll.setAttribute('aria-haspopup', 'dialog');
        openAll.setAttribute('aria-expanded', 'false');
        var status = el('span', 'lf-status');
        status.setAttribute('role', 'status');
        var clearAll = el('button', 'btn secondary sm lf-clear', 'Xóa tất cả bộ lọc');
        clearAll.type = 'button';
        bar.appendChild(openAll);
        bar.appendChild(status);
        bar.appendChild(clearAll);
        anchor.parentNode.insertBefore(bar, anchor);

        var syncs = [];

        function apply() {
            var active = cols.filter(function (c) { return c.value !== ''; });
            var shown = 0;
            rows.forEach(function (r) {
                var ok = active.every(function (c) {
                    var t = r.text[c.i];
                    return c.mode === 'select' ? t === c.value : norm(t).indexOf(norm(c.value)) !== -1;
                });
                if (ok) shown++;
                r.tr.hidden = !ok;
                r.extra.forEach(function (x) { x.hidden = !ok; });
            });
            cols.forEach(function (c) {
                var on = c.value !== '';
                c.btn.classList.toggle('active', on);
            });
            bar.classList.toggle('on', active.length > 0);
            status.textContent = active.length
                ? 'Hiện ' + shown + ' trong ' + rows.length + ' dòng. Đang lọc: ' + active.map(function (c) { return c.label.toLowerCase(); }).join(', ') + '.'
                : '';
            openAll.classList.toggle('active', active.length > 0);
            syncs.forEach(function (f) { f(); });
        }

        // Một ô lọc (dùng cho cả cửa sổ nhỏ từng cột và khung gồm mọi cột).
        function control(c, withLabel) {
            var wrapper = el('div', 'lf-field');
            var id = 'lf' + (++seq);
            var input;
            if (withLabel) {
                var lab = el('label', 'lf-label', c.label);
                lab.setAttribute('for', id);
                wrapper.appendChild(lab);
            }
            if (c.mode === 'select') {
                input = el('select');
                var all = el('option', null, 'Tất cả');
                all.value = '';
                input.appendChild(all);
                c.values.forEach(function (v) {
                    var o = el('option', null, v.length > 60 ? v.slice(0, 57) + '…' : v);
                    o.value = v;
                    input.appendChild(o);
                });
                input.addEventListener('change', function () { c.value = input.value; apply(); });
            } else {
                input = el('input');
                input.type = 'search';
                input.placeholder = 'Gõ chữ cần tìm…';
                input.setAttribute('autocomplete', 'off');
                input.addEventListener('input', function () { c.value = input.value.trim(); apply(); });
            }
            input.id = id;
            if (!withLabel) input.setAttribute('aria-label', 'Lọc theo ' + c.label.toLowerCase());
            wrapper.appendChild(input);
            syncs.push(function () { if (input.value !== c.value && document.activeElement !== input) input.value = c.value; });
            return { box: wrapper, input: input };
        }

        function panel(title) {
            var p = el('div', 'fpop');
            p.setAttribute('role', 'dialog');
            p.setAttribute('aria-label', title);
            p.hidden = true;
            var h = el('div', 'fpop-head');
            h.appendChild(el('strong', null, title));
            var x = el('button', 'fpop-x', '×');
            x.type = 'button';
            x.setAttribute('aria-label', 'Đóng');
            x.addEventListener('click', function () { window.FPop.close(true); });
            h.appendChild(x);
            p.appendChild(h);
            var body = el('div', 'fpop-body');
            p.appendChild(body);
            document.body.appendChild(p);
            return { root: p, body: body };
        }

        cols.forEach(function (c) {
            var btn = el('button', 'fbtn');
            btn.type = 'button';
            btn.innerHTML = SVG;
            var t = 'Lọc theo ' + c.label.toLowerCase();
            btn.title = t;
            btn.setAttribute('aria-label', t);
            btn.setAttribute('aria-expanded', 'false');
            btn.setAttribute('aria-haspopup', 'dialog');
            c.btn = btn;
            var inner = el('div', 'th-in');
            while (c.th.firstChild) inner.appendChild(c.th.firstChild);
            inner.appendChild(btn);
            c.th.appendChild(inner);

            var p = panel(t);
            var ctl = control(c, false);
            p.body.appendChild(ctl.box);
            var foot = el('div', 'fpop-foot');
            var clear = el('button', 'btn secondary sm', 'Bỏ lọc cột này');
            clear.type = 'button';
            clear.addEventListener('click', function () { c.value = ''; apply(); ctl.input.focus(); });
            foot.appendChild(clear);
            p.root.appendChild(foot);
            btn.addEventListener('click', function () { window.FPop.open(btn, p.root); });
            ctl.input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); window.FPop.close(true); }
            });
        });

        var all = panel('Lọc danh sách');
        cols.forEach(function (c) { all.body.appendChild(control(c, true).box); });
        var allFoot = el('div', 'fpop-foot');
        var done = el('button', 'btn sm', 'Xong');
        done.type = 'button';
        done.addEventListener('click', function () { window.FPop.close(true); });
        var allClear = el('button', 'btn secondary sm', 'Xóa tất cả bộ lọc');
        allClear.type = 'button';
        allClear.addEventListener('click', function () { cols.forEach(function (c) { c.value = ''; }); apply(); });
        allFoot.appendChild(done);
        allFoot.appendChild(allClear);
        all.root.appendChild(allFoot);
        openAll.addEventListener('click', function () { window.FPop.open(openAll, all.root); });
        clearAll.addEventListener('click', function () { cols.forEach(function (c) { c.value = ''; }); apply(); });

        apply();
    }

    document.querySelectorAll('table[data-filter]').forEach(setup);
    document.querySelectorAll('table[data-sticky]').forEach(pinShadow);
})();
