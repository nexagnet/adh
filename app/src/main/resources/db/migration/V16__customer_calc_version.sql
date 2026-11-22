-- "Mua gần nhất" đổi nghĩa: từ "ngày đơn mới nhất (kể cả hủy/hoàn)" thành "ngày của đơn giao thành công gần nhất",
-- và đơn "đã lấy hàng/đang giao" quá lâu bị coi là kẹt (chưa xác định, khách cần kiểm tra).
-- calc_version cho biết khách được tính theo phiên bản cách tính nào (CustomerService.CALC_VERSION); khi khởi động,
-- các khách còn ở phiên bản thấp hơn được tính lại (cùng cách GeoBackfill làm với khu vực). Mặc định 0 = chưa tính theo cách mới.
ALTER TABLE customer ADD COLUMN calc_version INT NOT NULL DEFAULT 0;
