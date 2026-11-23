const usd = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
const clock = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  timeZone: "UTC",
});

/** Minor units (cents) to "$25.99". Money is never a float in the API; only here, for display. */
export function formatUsd(amountMinor: number): string {
  return usd.format(amountMinor / 100);
}

/** The last 8 characters of an id: UUIDv7 ids share their time-based prefix, so the tail is what differs. */
export function shortId(id: string): string {
  return id.slice(-8);
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
  ["second", 1],
];

/** "3 minutes ago", "in 12 seconds", "now". */
export function formatRelative(iso: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  if (Math.abs(seconds) < 5) {
    return "now";
  }
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) {
      return relative.format(Math.round(seconds / size), unit);
    }
  }
  return "now";
}

/** "14:03:27 UTC". */
export function formatClock(iso: string): string {
  return `${clock.format(new Date(iso))} UTC`;
}
