package vn.shop.crm.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TikTokCatalogTest {
    @Test
    void onlyReturnsTheExactSkuWithShopPriceAndStock() {
        var result = TikTokCatalog.findSku(JsonMapper.builder().build().readTree("""
                {"code":0,"data":{"products":[{"id":"40","title":"Pin","status":"ACTIVATE","skus":[
                  {"id":"123","seller_sku":"PIN-12","price":{"currency":"VND","sale_price":"120000"},
                   "inventory":[{"warehouse_id":"1","quantity":4},{"warehouse_id":"2","quantity":6}]},
                  {"id":"999","seller_sku":"PIN-24","price":{"currency":"VND","sale_price":"160000"}}
                ]}]}}
                """), "PIN-12");
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.skuId()).isEqualTo("123");
            assertThat(item.price()).isEqualByComparingTo(new BigDecimal("120000"));
            assertThat(item.stock()).isEqualTo(10);
        });
    }

    @Test
    void missingStockDoesNotReadAsZero() {
        var result = TikTokCatalog.findSku(JsonMapper.builder().build().readTree("""
                {"data":{"products":[{"id":"40","title":"Pin","status":"ACTIVATE","skus":[
                {"id":"123","seller_sku":"PIN-12","price":{"currency":"VND","sale_price":"120000"}}]}]}}
                """), "PIN-12");
        assertThat(result).singleElement().satisfies(item -> assertThat(item.stock()).isNull());
    }
}
