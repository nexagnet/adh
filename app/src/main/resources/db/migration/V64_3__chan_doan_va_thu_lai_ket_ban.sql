ALTER TABLE invitation
    ADD COLUMN friend_transport_failures smallint NOT NULL DEFAULT 0 CHECK (friend_transport_failures BETWEEN 0 AND 4),
    ADD COLUMN friend_transport_retry_after timestamptz,
    ADD COLUMN friend_transport_code text;

ALTER TABLE zalo_friend_observation
    ADD COLUMN reason_code text,
    ADD COLUMN attempt_at timestamptz;

ALTER TABLE zalo_friend_observation_event
    ADD COLUMN reason_code text,
    ADD COLUMN attempt_at timestamptz;

-- V64.2 ghi mốc bắt đầu đọc nhưng chưa lưu mốc lần gửi. Chỉ nối bằng chứng cũ
-- với lần gửi hiện tại nếu việc đọc bắt đầu sau khi ghi lần gửi của đúng tài khoản.
UPDATE zalo_friend_observation o SET attempt_at = i.friend_request_at,
    last_confirmed_state = CASE WHEN o.last_confirmed_at >= i.friend_request_at THEN o.last_confirmed_state END,
    last_confirmed_at = CASE WHEN o.last_confirmed_at >= i.friend_request_at THEN o.last_confirmed_at END
FROM invitation i
WHERE i.id = o.invitation_id AND i.account_id = o.account_id AND o.observed_at >= i.friend_request_at;

UPDATE zalo_friend_observation_event e SET attempt_at = i.friend_request_at
FROM invitation i
WHERE i.id = e.invitation_id AND i.account_id = e.account_id AND e.observed_at >= i.friend_request_at;

CREATE INDEX zalo_msg_log_friend_review_idx ON zalo_msg_log (account_id, ref_id)
    WHERE kind = 'FRIEND' AND outcome IN ('DANG_GOI', 'KHONG_RO_DA_DONG');
