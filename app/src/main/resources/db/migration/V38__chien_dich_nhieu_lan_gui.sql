-- D21 PR 5a (23/11/2026): chuỗi tin mời nhiều lần (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.3), PHẦN DỮ LIỆU VÀ CẤU HÌNH.
-- Chưa có gì gửi tin nhắc ở bước này: bộ gửi và bộ nối làm ở PR 5b. Trần cứng 3 lần mỗi khách đặt cả ở mã (CampaignSteps.MAX_TOUCHES) lẫn ở đây.

-- Số lần gửi của chiến dịch (1 = đúng như D18 hiện nay). Bước 1 là Tin 1 (message_template), bước 2 và 3 là tin nhắc.
ALTER TABLE invitation_campaign ADD COLUMN max_touches INT NOT NULL DEFAULT 1 CHECK (max_touches BETWEEN 1 AND 3);

-- Tin nhắc của chiến dịch. gap_days là số ngày tối thiểu kể từ lần gửi thành công trước (sàn cứng 3 ngày).
CREATE TABLE campaign_step (
    campaign_id BIGINT NOT NULL REFERENCES invitation_campaign (id) ON DELETE CASCADE,
    step_no     INT    NOT NULL CHECK (step_no BETWEEN 2 AND 3),
    template    TEXT   NOT NULL CHECK (char_length(template) BETWEEN 1 AND 500),
    gap_days    INT    NOT NULL CHECK (gap_days BETWEEN 3 AND 30),
    PRIMARY KEY (campaign_id, step_no)
);

-- SỔ ĐẾM CỨNG: mỗi lần nhắn chủ động tới một lời mời là một dòng, khóa duy nhất (lời mời, bước) và step_no <= 3, nên không có đường nào
-- gửi quá 3 lần cho một khách, kể cả khi mã có lỗi. Bước 1 (Tin 1) cũng ghi vào đây từ PR 5b.
CREATE TABLE invitation_touch (
    invitation_id BIGINT      NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    step_no       INT         NOT NULL CHECK (step_no BETWEEN 1 AND 3),
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome       TEXT        NOT NULL CHECK (outcome IN ('SENT', 'FAILED', 'UNKNOWN')),
    PRIMARY KEY (invitation_id, step_no)
);
