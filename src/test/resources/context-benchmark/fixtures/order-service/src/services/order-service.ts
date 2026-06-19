import { saveOrder } from "../repositories/order-repository";
import type { Order } from "../types/order";

export function createOrder(customerId: string, sku: string): Order {
  const order: Order = { id: "order-1", customerId, sku, status: "created" };
  saveOrder(order);
  return order;
}

export function markOrderPaid(order: Order): Order {
  return { ...order, status: "paid" };
}
