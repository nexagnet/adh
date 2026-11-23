// Cửa sổ nhỏ (popover) dùng chung cho mọi bộ lọc cột: mở dưới nút phễu, bấm ra ngoài hoặc Esc thì đóng.
// Điện thoại (màn ≤ 700px): hiện thành khung trượt từ đáy màn hình, nền phía sau mờ đi.
(function () {
    var current = null;
    var phone = window.matchMedia('(max-width: 700px)');

    function place() {
        if (!current) return;
        var panel = current.panel;
        if (phone.matches) {
            panel.style.top = panel.style.left = panel.style.maxHeight = '';
            return;
        }
        var b = current.btn.getBoundingClientRect();
        var top = Math.max(8, Math.min(b.bottom + 6, window.innerHeight - 120));
        var width = panel.offsetWidth;
        panel.style.top = top + 'px';
        panel.style.left = Math.max(8, Math.min(b.left, window.innerWidth - width - 8)) + 'px';
        panel.style.maxHeight = Math.max(160, window.innerHeight - top - 12) + 'px';
    }

    function close(refocus) {
        if (!current) return;
        var c = current;
        current = null;
        c.panel.hidden = true;
        c.btn.setAttribute('aria-expanded', 'false');
        document.body.classList.remove('fpop-open');
        if (refocus) c.btn.focus();
        if (c.onClose) c.onClose();
    }

    function open(btn, panel, onClose) {
        if (current && current.panel === panel) { close(true); return; }
        close(false);
        if (panel.parentNode !== document.body) document.body.appendChild(panel);
        panel.hidden = false;
        btn.setAttribute('aria-expanded', 'true');
        document.body.classList.add('fpop-open');
        current = { btn: btn, panel: panel, onClose: onClose };
        place();
        var first = panel.querySelector('input:not([type=hidden]), select');
        if (first) first.focus();
    }

    document.addEventListener('pointerdown', function (e) {
        if (!current) return;
        if (current.panel.contains(e.target) || current.btn.contains(e.target)) return;
        close(false);
    });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && current) { e.preventDefault(); close(true); }
    });
    window.addEventListener('resize', place);
    window.addEventListener('scroll', function (e) {
        if (!current || current.panel.contains(e.target)) return;
        // Cuộn ngang trong bảng thì đóng (nút phễu đã dời chỗ); cuộn cả trang thì dời cửa sổ theo nút.
        if (e.target !== document && e.target !== window) close(false); else place();
    }, true);

    window.FPop = { open: open, close: close };
})();
