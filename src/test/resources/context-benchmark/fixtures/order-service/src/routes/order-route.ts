import { createOrder } from "../services/order-service";

export function postOrderRoute(body: { customerId: string; sku: string }) {
  return createOrder(body.customerId, body.sku);
}
