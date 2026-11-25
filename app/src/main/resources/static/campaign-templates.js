// Chọn mẫu ở form tạo chiến dịch: chỉ ĐIỀN các ô "Lọc khách kỹ hơn" từ giá trị gắn sẵn ở nút (data-values), không gửi gì về máy chủ.
(function () {
    var buttons = document.querySelectorAll('button[data-template]');
    var note = document.getElementById('template-note');
    if (!buttons.length) {
        return;
    }

    function setValue(form, key, value) {
        var field = form.elements[key];
        if (!field) {
            return;
        }
        var list = (field.length !== undefined && !field.options) ? Array.prototype.slice.call(field) : [field];
        var wanted = Array.isArray(value) ? value.map(String) : [String(value)];
        list.forEach(function (el) {
            if (el.type === 'radio') {
                el.checked = wanted.indexOf(el.value) >= 0;
            } else if (el.type === 'checkbox') {
                el.checked = typeof value === 'boolean' ? value : wanted.indexOf(el.value) >= 0;
            } else if (el.tagName === 'SELECT' && el.multiple) {
                Array.prototype.forEach.call(el.options, function (o) {
                    o.selected = wanted.indexOf(o.value) >= 0;
                });
            } else {
                el.value = wanted[0];
            }
        });
    }

    buttons.forEach(function (button) {
        button.addEventListener('click', function () {
            var form = button.form;
            var values;
            try {
                values = JSON.parse(button.getAttribute('data-values'));
            } catch (e) {
                return;
            }
            Object.keys(values).forEach(function (key) {
                setValue(form, key, values[key]);
            });
            var details = form.querySelector('details.more');
            if (details) {
                details.open = true;
            }
            if (note) {
                note.textContent = 'Đã điền sẵn mẫu "' + button.textContent + '" ở mục "Lọc khách kỹ hơn". Bấm "Xem trước số khách khớp" để kiểm tra rồi mới tạo.';
            }
            form.dispatchEvent(new Event('change', { bubbles: true }));
        });
    });
})();
