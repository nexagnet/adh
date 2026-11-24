# Thiết kế: chiến dịch tự nhận khách mới (chủ shop chốt 23/11/2026)

**Yêu cầu:** "Khi chiến dịch đang áp dụng thì những khách mới được đồng bộ hằng ngày cũng phải được gửi tin nhắn." Chủ shop chọn: **bỏ duyệt từng người, thay bằng tiêu chí duyệt sẵn của chiến dịch**, bật riêng, tắt mặc định.

## Vì sao cần
`InviteService.createCampaign` chỉ lấy ảnh chụp khách đủ điều kiện lúc tạo (tối đa 300). Khách nhập về sau đó phải được mẹ duyệt từng người (hoặc "duyệt tất cả ứng viên") rồi tạo chiến dịch mới. Với đồng bộ hằng ngày, việc này tự động hóa theo tiêu chí.

## Cách làm
- V30: `customer.first_seen_at` (khách có sẵn đặt về epoch, nên không bao giờ là "khách mới"); `invitation_campaign.auto_intake_since/by`, chỉ mục duy nhất để mỗi lúc chỉ MỘT chiến dịch tự nhận.
- `ZaloCampaignIntake`: `enable` (chỉ khi chiến dịch đã duyệt tự gửi), `disable`, `intake()` mỗi nhịp của `ZaloInviteSender.step()` (sau khi kiểm công tắc tự gửi và tạm dừng).
- **Tiêu chí duyệt sẵn** (`ContactService.newCandidates`): `first_seen_at` từ lúc bật; một lần mua; không cần kiểm tra; chưa từ chối (dòng sổ còn hiệu lực); chưa có dòng cơ sở liên hệ nào (không bị chặn, chưa duyệt tay); chưa từng được mời; đúng khu vực (`geo_scope`) của chiến dịch. Khách được duyệt qua đúng câu lệnh duyệt cũ (kiểm lại điều kiện), ghi `recorded_by = chien-dich-tu-nhan`, có sự kiện `BASIS_APPROVED`.
- Mỗi lời mời READY được tạo trong chiến dịch (`InviteService.addInvitations`), hạn dùng theo chiến dịch. Hàng chờ có trần: không quá `max-campaign` (300) và không quá hạn mức ngày x (số ngày link - 1), để khách không dồn đống rồi hết hạn. Mỗi nhịp nhận tối đa 50 khách.
- **Không đổi gì ở phía gửi:** Tin 1 vẫn qua `ZaloMessageService` (công tắc, nấc, 08:00–22:00, nghỉ cố định, cầu dao, 50 khách/ngày) và mỗi khách đúng một Tin 1.

## Không làm
Không tự duyệt khách đã có trước lúc bật (mẹ có thể đang chủ ý giữ lại); không tự duyệt khách cần kiểm tra, từ chối hay bị chặn; không nhận khi đã bỏ duyệt, tạm dừng, hoặc công tắc tự gửi tắt.
