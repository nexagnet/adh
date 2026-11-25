// Nút "Xem trước" ở form tạo chiến dịch: gửi chính các ô của form tới /campaigns/preview và chèn kết quả (chỉ đếm, không tạo gì).
(function () {
    var button = document.getElementById('preview-button');
    var box = document.getElementById('preview-box');
    var submit = document.getElementById('create-button');
    if (!button || !box) {
        return;
    }
    var form = button.form;
    button.addEventListener('click', function () {
        box.textContent = 'Đang đếm...';
        button.disabled = true;
        fetch(button.getAttribute('data-url'), {
            method: 'POST',
            body: new URLSearchParams(new FormData(form)),
            headers: { 'Accept': 'text/html' },
            credentials: 'same-origin'
        }).then(function (r) {
            return r.text();
        }).then(function (html) {
            var holder = document.createElement('div');
            holder.innerHTML = html;
            var node = holder.firstElementChild;
            box.textContent = '';
            if (node) {
                box.appendChild(node);
                if (submit) {
                    submit.disabled = node.getAttribute('data-matched') === '0';
                }
            }
        }).catch(function () {
            box.textContent = 'Không xem trước được, hãy thử lại.';
        }).then(function () {
            button.disabled = false;
        });
    });
    // Đổi bất kỳ ô nào thì kết quả cũ không còn đúng: bỏ khóa nút tạo và nhắc xem lại.
    form.addEventListener('change', function () {
        if (submit) {
            submit.disabled = false;
        }
        if (box.firstElementChild) {
            box.classList.add('stale');
        }
    });
})();
