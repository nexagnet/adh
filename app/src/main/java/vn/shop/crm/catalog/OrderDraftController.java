package vn.shop.crm.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "APP_ADH_STAGE2_ENABLED", havingValue = "true")
class OrderDraftController {
    private final JdbcClient jdbc;
    private final TikTokCatalog catalog;

    OrderDraftController(JdbcClient jdbc, TikTokCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @GetMapping("/handoffs/{id}/order-draft")
    String form(@PathVariable long id, @RequestParam(defaultValue = "") String sku, Model model) {
        String group = jdbc.sql("SELECT g.name FROM handoff h JOIN zalo_group g ON g.id = h.group_id WHERE h.id = :id")
                .param("id", id).query(String.class).optional().orElseThrow(() -> new ImportException("Không tìm thấy việc."));
        model.addAttribute("handoffId", id);
        model.addAttribute("group", group);
        model.addAttribute("configured", catalog.configured());
        model.addAttribute("sku", sku);
        model.addAttribute("items", sku.isBlank() ? List.of() : catalog.lookup(sku.strip()));
        model.addAttribute("token", UUID.randomUUID().toString());
        model.addAttribute("drafts", jdbc.sql("""
                SELECT product_name, seller_sku, quantity, observed_unit_price, currency, observed_stock, created_at
                FROM shop_order_draft WHERE handoff_id = :id ORDER BY id DESC LIMIT 50
                """).param("id", id).query().listOfRows());
        return "order-draft";
    }

    @PostMapping("/handoffs/{id}/order-draft")
    String draft(@PathVariable long id, @RequestParam String sku, @RequestParam String skuId,
                 @RequestParam UUID token, @RequestParam int quantity, Principal owner, RedirectAttributes redirect) {
        if (quantity < 1 || quantity > 100) {
            throw new ImportException("Số lượng phải từ 1 đến 100.");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM shop_order_draft WHERE handoff_id = :id AND token = :token)")
                .param("id", id).param("token", token).query(Boolean.class).single()) {
            return "redirect:/handoffs/" + id + "/order-draft";
        }
        var item = catalog.lookup(sku.strip()).stream().filter(s -> s.skuId().equals(skuId)).findFirst()
                .orElseThrow(() -> new ImportException("Không tìm được SKU đang bán."));
        if (item.name().isBlank() || item.name().length() > 500 || item.price() == null || !item.currency().matches("[A-Z]{3}")
                || item.stock() == null || item.stock() < quantity) {
            throw new ImportException("TikTok Shop chưa có đủ giá hoặc tồn cho số lượng này.");
        }
        int changed = jdbc.sql("""
                INSERT INTO shop_order_draft (handoff_id, token, product_id, sku_id, seller_sku, product_name,
                    quantity, observed_unit_price, currency, observed_stock, created_by)
                SELECT h.id, :token, :product, :skuId, :sku, :name, :qty, :price, :currency, :stock, :actor
                FROM handoff h WHERE h.id = :id AND h.status <> 'RESOLVED'
                ON CONFLICT (token) DO NOTHING
                """).param("id", id).param("token", token).param("product", item.productId()).param("skuId", item.skuId())
                .param("sku", item.sellerSku()).param("name", item.name()).param("qty", quantity).param("price", item.price())
                .param("currency", item.currency()).param("stock", item.stock()).param("actor", owner.getName()).update();
        if (changed == 1) {
            redirect.addFlashAttribute("flash", "Đã lưu đơn nháp.");
        }
        return "redirect:/handoffs/" + id + "/order-draft";
    }
}
