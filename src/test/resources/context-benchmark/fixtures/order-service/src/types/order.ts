export type OrderStatus = "created" | "paid" | "cancelled";

export interface Order {
  id: string;
  customerId: string;
  sku: string;
  status: OrderStatus;
}
