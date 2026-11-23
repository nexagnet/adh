-- D21 PR 5b-2 (23/11/2026): ghi lần ĐẦU khách (người đã nhận Tin 1 từ cầu nối) nhắn riêng lại. Có dấu này thì mọi tin nhắc của lời mời
-- đó dừng hẳn (khách đã tự lên tiếng, việc còn lại do trợ lý trả lời hoặc người thật). Chỉ ghi giờ, không ghi nội dung tin.
ALTER TABLE invitation ADD COLUMN inbound_at TIMESTAMPTZ;
