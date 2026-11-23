import {
  CircleCheck,
  CircleDashed,
  Copy,
  FlaskConical,
  History,
  Inbox,
  RotateCw,
  Send,
  ShieldX,
  Skull,
  TriangleAlert,
  UserCog,
  type LucideIcon,
} from "lucide-react";
import type { OrderDelivery, TimelineEntry } from "../api/client";
import { formatClock } from "./format";
import type { Tone } from "./status";

export interface TimelineItem {
  key: string;
  at: string;
  label: string;
  detail: string;
  icon: LucideIcon;
  tone: Tone;
}

type Detail = Record<string, unknown>;

function text(value: unknown): string {
  return value === undefined || value === null ? "" : String(value);
}

function eventSummary(d: Detail): string {
  const parts = [text(d.type)];
  if (d.sequence !== undefined) parts.push(`sequence ${text(d.sequence)}`);
  return parts.filter(Boolean).join(" · ");
}

/** What a stored audit row means for someone watching the order. */
function fromAudit(entry: TimelineEntry, index: number): TimelineItem {
  const d = (entry.detail ?? {}) as Detail;
  const base = { key: `audit-${index}`, at: entry.at };
  switch (entry.kind) {
    case "PAYMENT_EVENT_APPLIED":
      return {
        ...base,
        label: "Applied",
        detail: `${eventSummary(d)}: ${text(d.from)} → ${text(d.to)}`,
        icon: CircleCheck,
        tone: "success",
      };
    case "PAYMENT_EVENT_STALE":
      return {
        ...base,
        label: "Stale (out of order)",
        detail: `${eventSummary(d)} ignored: ${text(d.reason)}`,
        icon: History,
        tone: "warning",
      };
    case "DUPLICATE":
      return {
        ...base,
        label: "Deduplicated",
        detail: `${text(d.providerEventId)} was already processed; acknowledged, not applied again`,
        icon: Copy,
        tone: "info",
      };
    case "PAYMENT_EVENT_IGNORED":
      return { ...base, label: "Ignored", detail: text(d.reason), icon: CircleDashed, tone: "neutral" };
    case "PAYMENT_EVENT_FAILED":
      return {
        ...base,
        label: "Rejected by state machine",
        detail: `${eventSummary(d)}: ${text(d.reason)}`,
        icon: TriangleAlert,
        tone: "danger",
      };
    case "CHAOS_STEP":
      if (d.result === "invalid-signature") {
        return {
          ...base,
          label: "Signature rejected",
          detail: `${text(d.step)} (HTTP ${text(d.httpStatus)})`,
          icon: ShieldX,
          tone: "danger",
        };
      }
      return {
        ...base,
        label: "Scenario step",
        detail: d.httpStatus ? `${text(d.step)} (HTTP ${text(d.httpStatus)})` : text(d.step),
        icon: FlaskConical,
        tone: "neutral",
      };
    case "OUTBOX_REPLAYED":
      return {
        ...base,
        label: "Replayed by operator",
        detail: `notification #${text(d.outboxId)} by ${entry.actor}`,
        icon: UserCog,
        tone: "info",
      };
    default:
      return {
        ...base,
        label: humanise(entry.kind),
        detail: entry.actor,
        icon: CircleDashed,
        tone: "neutral",
      };
  }
}

function humanise(kind: string): string {
  const words = kind.toLowerCase().split("_");
  const sentence = words.join(" ");
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}

function fromInbox(entry: TimelineEntry, index: number): TimelineItem {
  const d = (entry.detail ?? {}) as Detail;
  return {
    key: `inbox-${index}`,
    at: entry.at,
    label: "Received",
    detail: `${eventSummary(d)} from ${entry.actor} (${text(entry.providerEventId)})`,
    icon: Inbox,
    tone: "neutral",
  };
}

/** One notification's current state; the outbox keeps the latest state, not every attempt. */
function fromDelivery(delivery: OrderDelivery): TimelineItem[] {
  const key = `outbox-${delivery.id}`;
  const queued: TimelineItem = {
    key: `${key}-queued`,
    at: delivery.createdAt,
    label: "Notification queued",
    detail: `${delivery.eventType} (#${delivery.id}) for the merchant`,
    icon: Send,
    tone: "neutral",
  };
  switch (delivery.status) {
    case "DELIVERED":
      return [
        queued,
        {
          key,
          at: delivery.deliveredAt ?? delivery.createdAt,
          label: "Outbox delivered",
          detail: `${delivery.eventType} acknowledged by the merchant after ${delivery.attempts} attempt(s)`,
          icon: CircleCheck,
          tone: "success",
        },
      ];
    case "DEAD":
      return [
        queued,
        {
          key,
          at: delivery.nextAttemptAt,
          label: "Dead-lettered",
          detail: `${delivery.eventType} gave up after ${delivery.attempts} attempts: ${text(delivery.lastError)}`,
          icon: Skull,
          tone: "danger",
        },
      ];
    case "PENDING":
      if (delivery.attempts === 0) return [queued];
      return [
        queued,
        {
          key,
          at: delivery.nextAttemptAt,
          label: "Retry scheduled",
          detail: `attempt ${delivery.attempts + 1} at ${formatClock(delivery.nextAttemptAt)}; last error: ${text(delivery.lastError)}`,
          icon: RotateCw,
          tone: "warning",
        },
      ];
    case "IN_FLIGHT":
      return [
        queued,
        {
          key,
          at: delivery.nextAttemptAt,
          label: "Delivering",
          detail: `attempt ${delivery.attempts + 1} in progress`,
          icon: Send,
          tone: "info",
        },
      ];
  }
}

/** Audit log, inbox and outbox merged into one list, oldest first (stable for equal times). */
export function buildTimeline(entries: TimelineEntry[], deliveries: OrderDelivery[]): TimelineItem[] {
  const items = [
    ...entries.map((e, i) => (e.source === "INBOX" ? fromInbox(e, i) : fromAudit(e, i))),
    ...deliveries.flatMap(fromDelivery),
  ];
  return items
    .map((item, order) => ({ item, order, time: new Date(item.at).getTime() }))
    .sort((a, b) => a.time - b.time || a.order - b.order)
    .map(({ item }) => item);
}
