-- Đếm lượt gọi AI (DeepSeek) mỗi ngày giờ Việt Nam để giữ trần chi phí. Chỉ lưu số đếm, KHÔNG lưu nội dung câu hỏi hay trả lời.
CREATE TABLE ai_usage (
    day      DATE    PRIMARY KEY,
    calls    INTEGER NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0
);
