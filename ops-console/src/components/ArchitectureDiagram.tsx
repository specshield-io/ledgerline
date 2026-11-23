/** The event flow, drawn with currentColor so it follows the light/dark theme. */
export function ArchitectureDiagram() {
  const box = "fill-white stroke-current dark:fill-zinc-900";
  const accent = "fill-sky-50 stroke-sky-700 dark:fill-sky-950 dark:stroke-sky-400";
  return (
    <svg
      viewBox="0 0 420 470"
      role="img"
      aria-labelledby="arch-title"
      aria-describedby="arch-desc"
      className="h-auto w-full text-zinc-700 dark:text-zinc-300"
    >
      <title id="arch-title">Ledgerline architecture</title>
      <desc id="arch-desc">
        MockPay sends signed webhooks to the receiver, which verifies the signature and stores each event once
        in the inbox. The sequencer applies events in order to the order, and in the same database transaction
        writes the audit log and an outbox row. The outbox relay delivers signed notifications to the
        merchant, retrying with backoff until delivered or dead-lettered.
      </desc>
      <defs>
        <marker
          id="arrow"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerWidth="7"
          markerHeight="7"
          orient="auto"
        >
          <path d="M0 0 10 5 0 10z" className="fill-current" />
        </marker>
      </defs>
      <g fontSize="12" textAnchor="middle" className="fill-current">
        <rect x="20" y="10" width="170" height="44" rx="6" className={box} />
        <text x="105" y="30">
          MockPay (simulator)
        </text>
        <text x="105" y="45" fontSize="10">
          signed HMAC webhooks
        </text>

        <rect x="230" y="10" width="170" height="44" rx="6" className={box} />
        <text x="315" y="30">
          Chaos API
        </text>
        <text x="315" y="45" fontSize="10">
          scripts MockPay
        </text>
        <line x1="230" y1="32" x2="192" y2="32" stroke="currentColor" markerEnd="url(#arrow)" />

        <line x1="105" y1="54" x2="105" y2="84" stroke="currentColor" markerEnd="url(#arrow)" />
        <rect x="20" y="86" width="380" height="44" rx="6" className={box} />
        <text x="210" y="106">
          Webhook receiver
        </text>
        <text x="210" y="121" fontSize="10">
          raw-byte HMAC, constant-time compare, 300 s window; bad signature: 400
        </text>

        <line x1="210" y1="130" x2="210" y2="160" stroke="currentColor" markerEnd="url(#arrow)" />
        <rect x="20" y="162" width="380" height="44" rx="6" className={box} />
        <text x="210" y="182">
          Inbox
        </text>
        <text x="210" y="197" fontSize="10">
          unique (provider, event id): duplicates acknowledged, not applied
        </text>

        <line x1="210" y1="206" x2="210" y2="236" stroke="currentColor" markerEnd="url(#arrow)" />
        <rect x="10" y="238" width="400" height="104" rx="8" className={accent} strokeDasharray="5 3" />
        <text x="210" y="256" fontSize="10">
          one database transaction, order row locked
        </text>
        <rect x="24" y="266" width="120" height="62" rx="6" className={box} />
        <text x="84" y="290">
          Sequencer
        </text>
        <text x="84" y="306" fontSize="10">
          in order; stale
        </text>
        <text x="84" y="319" fontSize="10">
          events ignored
        </text>
        <rect x="156" y="266" width="110" height="62" rx="6" className={box} />
        <text x="211" y="290">
          Order
        </text>
        <text x="211" y="306" fontSize="10">
          + audit log
        </text>
        <rect x="278" y="266" width="118" height="62" rx="6" className={box} />
        <text x="337" y="290">
          Outbox row
        </text>
        <text x="337" y="306" fontSize="10">
          order.paid, …
        </text>
        <line x1="144" y1="297" x2="154" y2="297" stroke="currentColor" markerEnd="url(#arrow)" />
        <line x1="266" y1="297" x2="276" y2="297" stroke="currentColor" markerEnd="url(#arrow)" />

        <line x1="337" y1="342" x2="337" y2="372" stroke="currentColor" markerEnd="url(#arrow)" />
        <rect x="230" y="374" width="170" height="44" rx="6" className={box} />
        <text x="315" y="394">
          Outbox relay
        </text>
        <text x="315" y="409" fontSize="10">
          SKIP LOCKED, backoff + jitter
        </text>
        <line x1="230" y1="396" x2="192" y2="396" stroke="currentColor" markerEnd="url(#arrow)" />
        <rect x="20" y="374" width="170" height="44" rx="6" className={box} />
        <text x="105" y="394">
          Merchant
        </text>
        <text x="105" y="409" fontSize="10">
          signed, at-least-once
        </text>
        <text x="315" y="446" fontSize="10">
          after 8 failed attempts: dead letter,
        </text>
        <text x="315" y="460" fontSize="10">
          replayed by an ADMIN
        </text>
      </g>
    </svg>
  );
}
