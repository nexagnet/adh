# Thiết kế: trợ lý AI tự trả lời khách nhắn riêng (D20)

Ngày 23/11/2026, chủ dự án chốt: "tôi muốn AI tự sinh câu trả lời". Khách tự nhắn riêng thì trả lời hết, không giới hạn số câu; câu hỏi ngoài việc **mời vào nhóm** và **ưu đãi khi vào nhóm** thì chuyển người thật. Phạm vi này là hết giai đoạn 1 (máy 2 GB RAM).

## Đường đi của một tin
1. Bộ nối đọc tin riêng của người đã nhận Tin 1 hoặc Tin 2 (như D19). Khách đang trong luồng mời (SENT/CLICKED/FRIENDED) hoặc đã vào nhóm (JOINED) và không có cờ từ chối còn hiệu lực. Khách từng từ chối đi đường "khách quay lại", không bao giờ vào đây.
2. AI gắn nhãn (D19): từ chối thì xử lý như cũ (ghi sổ hoặc tạm dừng, không trả lời). `MUON_MUA` và `KHIEU_NAI` chuyển người thật ngay, không gọi AI thêm.
3. Các nhãn còn lại: AI phân loại phạm vi `TRONG`/`NGOAI`. Nghi ngờ là `NGOAI`; cần độ chắc từ 0,8 mới coi là trong phạm vi.
4. AI soạn MỘT câu CHỈ từ **bảng thông tin cửa hàng** (nguồn duy nhất). Nếu bảng không đủ thì AI trả rỗng và việc chuyển người thật.
5. `ReplyValidator` kiểm cứng (không dùng AI kiểm AI), rồi hệ thống thêm chữ ký "Trợ lý AI của <shop>" và cách gặp người thật.
6. Nấc **Chỉ soạn nháp**: lưu nháp, KHÔNG gửi. Nấc **Tự động**: xếp hàng, `ZaloReplySender` gửi qua đường chung (công tắc, khung giờ 08:00–20:00, khoảng nghỉ cố định, cầu dao, phanh khẩn cấp của bộ nối). Mỗi tin đến một câu trả lời; không tự gửi lại khi lỗi không rõ.

## Bộ kiểm cứng
Chặn: câu rỗng hoặc quá 500 ký tự; đường dẫn; số điện thoại, email, chuỗi số dài; **mọi con số không có trong bảng thông tin** (nên không bịa giá, phần trăm, thời hạn); nhắc nền tảng khác ngoài TikTok; hứa hẹn ("cam kết", "đảm bảo", "chắc chắn", tặng quà, mã giảm); đổi trả và hoàn tiền; chê hàng hoặc đối thủ; giả làm người hoặc chủ shop; xin địa chỉ, số điện thoại, mật khẩu, chuyển khoản. Các chữ "bảo hành", "còn hàng", "hết hàng", "tồn kho", "giá bán", "TikTok", đơn vị tiền chỉ được nhắc khi bảng thông tin cũng có chữ đó. Khớp không phân biệt dấu. Câu không qua kiểm thì không bao giờ gửi.

## Bảng thông tin cửa hàng (sửa ở `/zalo/reply`)
Mặc định (chủ shop chốt 23/11): cửa hàng bán chủ yếu **áo điều hòa và phụ kiện áo điều hòa (trong đó có pin)**; ưu đãi vào nhóm là giảm 10% so với giá TikTok và miễn phí ship; lý do: TikTok thu phí sàn nên mua qua Zalo shop nhường lại 10%, TikTok khó mua hơn, liên hệ bảo hành qua Zalo thuận tiện hơn; cách vào nhóm. Ô bảo hành để trống (AI không nói gì cụ thể). Không ghi chính sách đổi trả của TikTok vào bảng; AI không chủ động nhắc và được chỉ dẫn không nói.

## Dữ liệu và an toàn
- Chỉ gửi AI văn bản đã che (`PiiMasker`), không tên, số điện thoại, địa chỉ, mã Zalo hay lịch sử; tin của khách là dữ liệu không đáng tin (bọc giữa `<<<` `>>>`, đầu ra phải đúng khuôn JSON).
- Mỗi lượt đi qua `AiGate` (trần ngày, cầu dao). Mỗi tin dùng tối đa 3 lượt (nhãn, phạm vi, soạn); nên đặt `APP_AI_DAILY_LIMIT` đủ rộng.
- Bảng `reply_draft` (V31): chữ khách (đã che) và câu AI soạn tự xóa sau 30 ngày; không lưu số điện thoại. Không bao giờ tự xóa số điện thoại hay khách (CLAUDE.md mục 5a).
- Bộ nối `POST /messages` có thêm loại `REPLY`, cùng chốt chặn với `WELCOME`: chỉ theo mã Zalo, cần cờ đọc tin riêng, chỉ người vừa nhắn trong 24 giờ, một lần cho mỗi tin đến.
- Việc chuyển người thật là **hàng đợi chung** ở trang "Việc cần xử lý" (cột `assigned_to` để dành cho giai đoạn 2). Khách nhắn thêm khi việc đang chờ thì chữ được ghi tiếp vào cùng việc, không gọi AI chen vào.
- Có ô "Thử một câu" ở `/zalo/reply` để chạy chuỗi trên câu giả mà không lưu hay gửi gì. Nên thử vài chục câu (trong và ngoài phạm vi, câu cố lừa AI) trước khi bật.

## Không làm ở giai đoạn 1
AI trả lời trong nhóm Zalo, nhiều người trong đội bán hàng (Chatwoot), tra cứu nhiều sản phẩm, tóm tắt việc.
