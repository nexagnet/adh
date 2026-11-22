-- Gửi link nhóm cho khách tự bấm (22/11/2026): sau khi khách đồng ý kết bạn, người gửi (mẹ) dán tin kèm link nhóm Zalo
-- vào cuộc trò chuyện rồi bấm "Đã gửi link nhóm". Hệ thống KHÔNG tự gửi. Cột này chỉ ghi lại việc đó để mỗi khách
-- chỉ nhận tin link nhóm một lần và để bộ nối Zalo (D16) không thêm khách này vào nhóm lần nữa.
ALTER TABLE invitation ADD COLUMN group_link_sent_at TIMESTAMPTZ;
