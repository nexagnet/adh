package vn.shop.crm.zalo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Định tuyến mọi lời gọi {@link ZaloLink} tới cầu nối của TÀI KHOẢN đang làm việc ({@link ZaloAccountContext#current()}), D23. Mỗi tài khoản có
 * một {@link ZaloLink} riêng (đã bọc {@link SafeZaloLink}, nên lưới chế độ thử vẫn áp cho từng tài khoản). Đây là lớp định tuyến DUY NHẤT: dùng
 * {@link Proxy} để không thể sót một hàm nào khi thêm việc mới vào {@link ZaloLink}.
 *
 * <p>Tài khoản chưa khai báo cầu nối thì lời gọi bị từ chối bằng ngoại lệ (đóng kín), không bao giờ rơi sang cầu nối của tài khoản khác.
 */
public final class ZaloAccountRoutes {

    private final Map<Long, ZaloLink> links;
    private final ZaloLink router;
    private final boolean restoreLocked;

    public ZaloAccountRoutes(Map<Long, ZaloLink> links) {
        this(links, false);
    }

    ZaloAccountRoutes(Map<Long, ZaloLink> links, boolean restoreLocked) {
        if (links == null || links.isEmpty()) {
            throw new IllegalArgumentException("Cần ít nhất một tài khoản Zalo");
        }
        this.links = new LinkedHashMap<>(links);
        this.restoreLocked = restoreLocked;
        this.router = (ZaloLink) Proxy.newProxyInstance(ZaloLink.class.getClassLoader(), new Class<?>[]{ZaloLink.class}, new Handler());
    }

    /** Đường nối duy nhất để đăng ký làm bean {@link ZaloLink}. */
    public ZaloLink router() {
        return router;
    }

    /** Mã các tài khoản đã khai báo cầu nối, theo thứ tự khai báo. */
    public List<Long> accountIds() {
        return List.copyOf(links.keySet());
    }

    /** Cầu nối (đã bọc lưới an toàn) của một tài khoản; dùng cho test và trang quản trị, không để gọi Zalo vòng qua định tuyến. */
    ZaloLink target(long accountId) {
        return links.get(accountId);
    }

    public boolean has(long accountId) {
        return links.containsKey(accountId);
    }

    private final class Handler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "ZaloAccountRoutes" + links.keySet();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            long account = ZaloAccountContext.current();
            ZaloLink target = links.get(account);
            if (target == null) {
                throw new IllegalStateException("Tài khoản Zalo " + account + " chưa khai báo cầu nối.");
            }
            if (restoreLocked) {
                Object blocked = ZaloRestoreFence.blockedResult(method.getReturnType());
                if (blocked != null) {
                    return blocked;
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
