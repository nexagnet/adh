-- Mẫu bộ lọc khách do chủ shop tự lưu (PR 5 của "cấu hình chiến dịch linh động"). Chỉ chứa tên và bộ lọc
-- (cùng khuôn invitation_campaign.customer_filter): KHÔNG có khách, số điện thoại hay nội dung tin.
CREATE TABLE campaign_template (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(60) NOT NULL,
    filter      JSONB NOT NULL,
    created_by  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX campaign_template_name_uq ON campaign_template (lower(name));
