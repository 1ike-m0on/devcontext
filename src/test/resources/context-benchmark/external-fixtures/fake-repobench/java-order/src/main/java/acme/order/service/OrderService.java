package acme.order.service;

import acme.order.model.Order;
import acme.order.repository.OrderRepository;

public class OrderService {
    private final OrderRepository repository = new OrderRepository();

    public Order create(String sku, int quantity) {
        Order order = new Order(sku, quantity);
        return repository.save(order);
    }
}
