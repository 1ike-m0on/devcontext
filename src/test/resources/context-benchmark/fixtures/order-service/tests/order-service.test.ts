import { createOrder } from "../src/services/order-service";

test("createOrder returns a created order", () => {
  const order = createOrder("customer-1", "sku-1");
  expect(order.status).toBe("created");
});
