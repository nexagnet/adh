-- Phân vùng địa lý: tỉnh/thành đoán từ địa chỉ (chỉ lưu mã tỉnh, không lưu thêm gì), khu vực của nhóm Zalo,
-- và tỉnh khách tự chọn trên trang giấy giới thiệu.
ALTER TABLE customer ADD COLUMN province TEXT;
-- Phiên bản cách đoán đã dùng; khi sửa cách đoán, hệ thống tự tính lại cho khách có phiên bản cũ.
ALTER TABLE customer ADD COLUMN geo_version INT NOT NULL DEFAULT 0;
CREATE INDEX idx_customer_province ON customer (province);

-- Khu vực nhóm nhận khách: NULL = nhóm chung (nhận mọi nơi); 'R:NAM' miền; 'S:DONG_NAM_BO' vùng; 'P:can-tho' tỉnh.
ALTER TABLE zalo_group ADD COLUMN geo_scope TEXT;

-- Lượt vào bằng mã QR chung: có thể chưa có nhóm (khách đã chọn tỉnh nhưng chưa có nhóm hợp) -> group_id trống,
-- dùng để biết khu vực nào đang thiếu nhóm. Vẫn không lưu số điện thoại, tên, địa chỉ mạng.
ALTER TABLE open_entry ALTER COLUMN group_id DROP NOT NULL;
ALTER TABLE open_entry ADD COLUMN province TEXT;
