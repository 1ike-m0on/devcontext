import type { Order } from "../types/order";

const orders = new Map<string, Order>();

export function saveOrder(order: Order) {
  orders.set(order.id, order);
}

export function findOrder(id: string): Order | undefined {
  return orders.get(id);
}
