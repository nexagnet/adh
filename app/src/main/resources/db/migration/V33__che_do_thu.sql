-- CHẾ ĐỘ THỬ (23/11/2026): mặc định hệ thống chỉ được liên hệ (nhắn, thêm nhóm, tạo nhóm, tự nhận, AI trả lời) những SỐ ĐIỆN THOẠI
-- nằm trong danh sách này, kể cả khi công tắc tự gửi đang bật. Khách thật không nằm trong danh sách nên không bao giờ bị chạm tới.
-- Chế độ chạy thật chỉ bật được bằng biến môi trường trên máy chủ (APP_ZALO_LIVE=true), không bật được từ giao diện.
-- Chỉ chứa số của chính chủ shop hoặc người đã đồng ý nhận tin thử (E.164, cùng dạng với customer.phone). Chủ shop tự xóa được bất cứ lúc nào.
CREATE TABLE zalo_test_number (
    phone      TEXT        PRIMARY KEY,
    note       TEXT,
    created_by TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
