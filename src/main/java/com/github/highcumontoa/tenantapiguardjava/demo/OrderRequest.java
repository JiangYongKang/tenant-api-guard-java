package com.github.highcumontoa.tenantapiguardjava.demo;

/**
 * 示例写请求。
 */
public class OrderRequest {
    private String product;
    private int quantity;

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
