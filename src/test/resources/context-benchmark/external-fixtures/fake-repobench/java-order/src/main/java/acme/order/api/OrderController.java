package acme.order.api;

import acme.order.model.Order;
import acme.order.service.OrderService;

public class OrderController {
    private final OrderService orderService = new OrderService();

    public Order createOrder(String sku, int quantity) {
        return orderService.create(sku, quantity);
    }
}
