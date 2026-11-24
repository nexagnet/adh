-- Xóa Chiến dịch thử (24/11/2026): chủ dự án chốt chạy thử trên các số thử đã khai báo ở "Chế độ thử" bằng chính luồng thật
-- (chiến dịch thật, Tin 1, tự gửi lời mời kết bạn, tự thêm vào nhóm), không dùng màn riêng. Bảng danh sách số thử (zalo_test_number) và
-- dữ liệu khách KHÔNG bị đụng. Hai bảng dưới chỉ chứa số thử và kết quả thử, tách hẳn khách thật (không khóa ngoại tới customer).
DROP TABLE IF EXISTS test_target;
DROP TABLE IF EXISTS test_run;
