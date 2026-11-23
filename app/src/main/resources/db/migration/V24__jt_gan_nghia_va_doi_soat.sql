-- 23/11/2026, chủ shop dặn: tự gán nghĩa các trạng thái J&T còn để trống và cho file đối soát "đối chiếu cho chính xác".
--
-- 1) "Kí nhận CPN" và "Đã đối soát": trên cổng J&T hai thẻ này là mục con của thẻ "Giao thành công" (quan sát 21/11/2026,
--    docs/BAN-GIAO.md mục 8), nên gán là giao thành công. "Kết thúc" và "Kiện vấn đề" vẫn KHÔNG gán theo tên (Kết thúc có thể là
--    kết thúc sau khi hoàn; Kiện vấn đề là kiện có sự cố): chúng chỉ được tính là giao thành công khi file đối soát chứng minh
--    (mục 3). Chủ shop vẫn sửa được ở màn Trạng thái đơn.
INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status) VALUES
    ('JT_EXPRESS', 'ki nhan cpn', 'Kí nhận CPN',  'DELIVERED'),
    ('JT_EXPRESS', 'da doi soat', 'Đã đối soát', 'DELIVERED')
ON CONFLICT (carrier, status_key) DO NOTHING;

-- 2) Đơn giao thành công nhờ file đối soát được đánh dấu để lần nhập file đơn sau không kéo trạng thái về như cũ.
ALTER TABLE shipment ADD COLUMN settled_delivered BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE shipment SET canonical_status = 'DELIVERED', updated_at = now()
WHERE carrier = 'JT_EXPRESS' AND status_key IN ('ki nhan cpn', 'da doi soat') AND canonical_status = 'UNKNOWN';

-- 3) Bằng chứng từ file đối soát: có thời gian ký nhận, J&T đã thu COD (> 0) và không có khoản phí hoàn ở bất kỳ kỳ nào.
--    Chỉ nâng đơn đang ở trạng thái chưa rõ, chờ lấy hoặc đang chạy; đơn hủy hoặc hoàn không bao giờ bị nâng.
UPDATE shipment s
SET canonical_status = 'DELIVERED', settled_delivered = TRUE,
    delivered_on = COALESCE(s.delivered_on, x.signed), updated_at = now()
FROM (SELECT tracking_no, max(signed_at) AS signed
      FROM settlement_line
      GROUP BY tracking_no
      HAVING bool_or(signed_at IS NOT NULL AND cod_collected > 0 AND return_fee = 0)
         AND NOT bool_or(return_fee > 0)) x
WHERE s.carrier = 'JT_EXPRESS' AND s.tracking_no = x.tracking_no
  AND s.canonical_status IN ('UNKNOWN', 'CREATED', 'IN_TRANSIT');
