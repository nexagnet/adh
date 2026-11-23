# Thiết kế: AI phân loại bảo hành và khiếu nại (23/11/2026)

Chủ dự án chốt: khách gửi mô tả lỗi, AI phân loại và tạo việc cho mẹ kèm đơn đã mua để mẹ khỏi tra cứu. Phần này **không bao giờ tự gửi gì cho khách** và không hứa bảo hành hay hoàn tiền.

## Đường đi của một tin
1. Bộ nối đọc tin riêng (D19), AI gắn nhãn như cũ. Nhãn `KHIEU_NAI` (phàn nàn, hàng lỗi, đổi trả) là cửa vào; nhãn `TU_CHOI` luôn đi đường từ chối riêng, không bao giờ vào đây.
2. Công tắc `warranty.enabled` (trang `/zalo/reply`, mục "Phân loại bảo hành và khiếu nại", **tắt mặc định**) phải bật. Không cần bật nấc trả lời; cần cùng ba cờ đọc tin riêng của D19 (`APP_ZALO_INBOX_ENABLED`, `BRIDGE_INBOX_ENABLED`, `APP_AI_ENABLED`).
3. Khách đã có việc đang mở: chữ mới được ghi tiếp vào việc đó (đếm số tin), **không gọi AI thêm** và không để AI trả lời chen vào. Mỗi khách (mã Zalo) tối đa một việc đang mở (chỉ mục duy nhất `uq_warranty_case_open`).
4. Chưa có việc: một lượt AI (`WarrantyAssistant`) trả đúng một khuôn JSON gồm loại (`LOI_SAN_PHAM`, `GIAO_HANG`, `HUONG_DAN`, `HOAN_TIEN`, `KHAC`), mức gấp (`CAO`, `THUONG`, `THAP`), tóm tắt, có nên xin ảnh hay không, và một **nháp** trả lời. Mức `CAO` dành cho nguy cơ an toàn (pin nóng, phồng, mùi khét, chập...) hoặc khách rất bức xúc; do AI hiểu câu, không có luật từ khóa.
5. Nháp qua bộ kiểm cứng riêng (`WarrantyService.checkDraft`): chặn con số, đường dẫn, số điện thoại/email, và lời hứa hay cam kết (cam kết, đảm bảo, đổi mới, hoàn tiền, đền bù, miễn phí, quà, xin địa chỉ/số điện thoại, giả làm chủ shop). Không qua thì **bỏ nháp**, việc vẫn được tạo.
6. AI lỗi, hết hạn mức, sai khuôn hay nhãn lạ: vẫn tạo việc, loại "Chưa phân loại", mức "Chưa rõ", kèm chữ khách đã che. Việc không bao giờ bị mất vì AI hỏng (nhưng nếu AI hỏng ngay ở bước gắn nhãn D19 thì tin không được phân loại, như các phần AI khác).
7. Việc hiện ở trang "Việc cần người thật xử lý" (mục "Bảo hành và khiếu nại", gấp xếp trước), kèm tối đa 3 đơn gần nhất của đúng khách đó (tên hàng, hãng, mã vận đơn, ngày tạo, ngày giao, trạng thái). Đơn được tra lúc hiển thị, không sao chép vào bảng. Số việc mở cộng vào số trên menu và Tổng quan.
8. Người thật tự trả lời trên Zalo thật rồi bấm "Đã xử lý": chữ khách, tóm tắt và nháp bị xóa ngay.

## Dữ liệu và an toàn
- AI chỉ nhận: văn bản khách đã che (`PiiMasker`), và vài dòng "hàng đã mua" gồm **tên hàng đã che và số ngày kể từ khi giao**. Không bao giờ tên khách, số điện thoại, địa chỉ, mã vận đơn, mã đơn hay mã Zalo. Chỉ nạp đơn của đúng khách đang nhắn (test khóa: đơn khách khác không lọt vào lời gọi).
- Tin khách và dòng hàng là dữ liệu không đáng tin (bọc giữa `<<<` `>>>` và `[[[` `]]]`); đầu ra sai khuôn thì không dùng.
- Bảng `warranty_case` (V35): chữ khách (đã che), tóm tắt và nháp tự xóa sau 30 ngày hoặc khi xử lý xong; không lưu số điện thoại. Nút "Xóa dữ liệu khách" của chủ shop xóa luôn việc (ON DELETE CASCADE). Không bao giờ tự xóa khách (CLAUDE.md mục 5a).
- Không có đường gửi nào ở phần này: không dùng `ZaloMessenger`, không tạo `zalo_msg_log`, không đổi hạn mức hay cầu dao.

## Ảnh và video: chưa làm
Bộ nối chỉ đọc chữ của tin riêng (cố ý, D19) và mô hình hiện dùng (DeepSeek) chỉ nhận chữ. Vì vậy hệ thống **không đọc ảnh**; AI chỉ gợi ý "nên xin khách ảnh hoặc video", mẹ xem ảnh trên Zalo. Muốn đọc ảnh cần (a) mô hình hỗ trợ ảnh, (b) bộ nối báo cho Adh biết tin có ảnh (chưa có), (c) duyệt thêm vì ảnh khách có thể chứa thông tin cá nhân. Chưa làm khi chưa có lời duyệt của chủ dự án.

## Chưa làm / giới hạn
- Cửa vào là nhãn `KHIEU_NAI` của bước gắn nhãn D19; câu hỏi lỗi mà AI gắn `CAU_HOI` sẽ đi đường trả lời thường (chuyển người thật nếu ngoài phạm vi). Nếu thực tế thấy sót, chỉnh lời nhắc gắn nhãn sau khi thử với tin thật.
- Chỉ phân loại khách đã nhận tin của hệ thống (có lời mời hoặc đã vào nhóm); người lạ nhắn vào không được xử lý (như D19).
- Chưa thử với tin Zalo và DeepSeek thật (như các phần AI khác): nên thử vài chục câu giả và theo dõi mục này vài ngày trước khi tin vào mức gấp.
- Cột `assigned_to` để dành cho đội bán hàng ở giai đoạn 2.
