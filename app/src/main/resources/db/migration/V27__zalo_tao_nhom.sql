-- Tự tạo nhóm Zalo theo khu vực (D19, docs/THIET-KE-TU-TAO-NHOM-ZALO.md). Mỗi dòng là MỘT đề xuất tạo nhóm: hệ thống đề xuất,
-- mẹ duyệt (nấc "Xác nhận") hoặc tự chạy (nấc "Tự động"), rồi gọi bộ nối đúng một lần. Không có tên khách, số điện thoại hay mã Zalo.
CREATE TABLE zalo_group_proposal (
    id            BIGSERIAL PRIMARY KEY,
    scope         TEXT        NOT NULL,           -- mã khu vực như nhóm Zalo: R:NAM, S:DONG_NAM_BO, P:can-tho
    group_name    TEXT        NOT NULL,
    reason        TEXT        NOT NULL,           -- "tạo nhóm vì có N khách chờ ở <khu vực>"
    waiting_count INT         NOT NULL,
    -- PROPOSED chờ mẹ duyệt; APPROVED đã duyệt chờ tới lượt; CREATING đã gọi cầu nối (chưa biết kết quả);
    -- DONE đã tạo; FAILED lỗi (không tự thử lại); REJECTED mẹ từ chối; EXPIRED không còn đủ khách hoặc quá hạn
    state         TEXT        NOT NULL CHECK (state IN ('PROPOSED', 'APPROVED', 'CREATING', 'DONE', 'FAILED', 'REJECTED', 'EXPIRED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by    TEXT,
    decided_at    TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,                    -- lúc gọi cầu nối: chỉ các dòng có giá trị này mới tính vào trần mỗi ngày
    finished_at   TIMESTAMPTZ,
    result_code   TEXT,                           -- mã ngắn (chữ, số, gạch), không chứa dữ liệu khách
    group_id      BIGINT      REFERENCES zalo_group (id) ON DELETE SET NULL,
    added_count   INT,
    link_ok       BOOLEAN,
    deputy_state  TEXT                            -- OK, FAILED hoặc NONE
);
CREATE INDEX idx_zalo_group_proposal_state ON zalo_group_proposal (state, created_at);

-- Khách nằm trong đề xuất (tối đa 5). Xóa lời mời thì dòng này tự mất.
CREATE TABLE zalo_group_proposal_member (
    proposal_id   BIGINT NOT NULL REFERENCES zalo_group_proposal (id) ON DELETE CASCADE,
    invitation_id BIGINT NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    PRIMARY KEY (proposal_id, invitation_id)
);
