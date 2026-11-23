import {
  Ban,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock,
  RotateCcw,
  Send,
  Skull,
  Undo2,
  type LucideIcon,
} from "lucide-react";
import type { OrderStatus, OutboxStatus } from "../api/client";

export type Tone = "neutral" | "info" | "success" | "warning" | "danger";

export interface Presentation {
  label: string;
  icon: LucideIcon;
  tone: Tone;
}

/** Every status has a label and an icon as well as a colour: colour is never the only signal. */
export const ORDER_STATUS: Record<OrderStatus, Presentation> = {
  CREATED: { label: "Created", icon: CircleDashed, tone: "neutral" },
  PAYMENT_PENDING: { label: "Payment pending", icon: Clock, tone: "info" },
  PAID: { label: "Paid", icon: CircleCheck, tone: "success" },
  PAYMENT_FAILED: { label: "Payment failed", icon: CircleX, tone: "danger" },
  PARTIALLY_REFUNDED: { label: "Partially refunded", icon: RotateCcw, tone: "warning" },
  REFUNDED: { label: "Refunded", icon: Undo2, tone: "warning" },
  CANCELLED: { label: "Cancelled", icon: Ban, tone: "neutral" },
};

export const OUTBOX_STATUS: Record<OutboxStatus, Presentation> = {
  PENDING: { label: "Pending", icon: Clock, tone: "info" },
  IN_FLIGHT: { label: "Delivering", icon: Send, tone: "info" },
  DELIVERED: { label: "Delivered", icon: CircleCheck, tone: "success" },
  DEAD: { label: "Dead-lettered", icon: Skull, tone: "danger" },
};

export const ORDER_STATUSES = Object.keys(ORDER_STATUS) as OrderStatus[];
export const OUTBOX_STATUSES = Object.keys(OUTBOX_STATUS) as OutboxStatus[];

/** Badge colours with WCAG AA contrast in both themes. */
export const TONE_CLASSES: Record<Tone, string> = {
  neutral: "bg-zinc-100 text-zinc-800 ring-zinc-300 dark:bg-zinc-800 dark:text-zinc-100 dark:ring-zinc-600",
  info: "bg-sky-50 text-sky-900 ring-sky-300 dark:bg-sky-950 dark:text-sky-100 dark:ring-sky-700",
  success:
    "bg-emerald-50 text-emerald-900 ring-emerald-300 dark:bg-emerald-950 dark:text-emerald-100 dark:ring-emerald-700",
  warning:
    "bg-amber-50 text-amber-900 ring-amber-300 dark:bg-amber-950 dark:text-amber-100 dark:ring-amber-700",
  danger: "bg-rose-50 text-rose-900 ring-rose-300 dark:bg-rose-950 dark:text-rose-100 dark:ring-rose-700",
};
