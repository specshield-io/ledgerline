import { Dialog } from "radix-ui";
import { BookOpen, ExternalLink, X } from "lucide-react";
import { ArchitectureDiagram } from "./ArchitectureDiagram";
import { REPOSITORY_URL } from "./Banner";

const DOCS = [
  {
    href: `${REPOSITORY_URL}/blob/main/docs/adr/0002-webhook-signature-and-inbox.md`,
    label: "ADR 0002: webhook signatures and the inbox",
  },
  {
    href: `${REPOSITORY_URL}/blob/main/docs/adr/0003-transactional-outbox.md`,
    label: "ADR 0003: the transactional outbox",
  },
  { href: `${REPOSITORY_URL}#try-it`, label: "README: try every scenario with curl" },
];

export function HowItWorks() {
  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <button type="button" className="btn">
          <BookOpen aria-hidden="true" className="size-4" />
          How it works
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-zinc-950/40" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col bg-white shadow-xl focus:outline-none dark:bg-zinc-950">
          <header className="flex items-start justify-between border-b border-zinc-200 p-4 dark:border-zinc-800">
            <div>
              <Dialog.Title className="text-lg font-semibold">How it works</Dialog.Title>
              <Dialog.Description className="text-sm text-zinc-700 dark:text-zinc-300">
                From a provider webhook to a merchant notification.
              </Dialog.Description>
            </div>
            <Dialog.Close aria-label="Close how it works" className="btn-icon">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </header>
          <div
            className="flex-1 space-y-4 overflow-y-auto p-4 text-sm"
            tabIndex={0}
            role="region"
            aria-label="Architecture and design documents"
          >
            <ArchitectureDiagram />
            <ul className="space-y-2">
              <li>
                <strong>Exactly-once effect:</strong> the inbox stores each event once; repeats are
                acknowledged (Deduplicated) without changing the order.
              </li>
              <li>
                <strong>Order of events:</strong> each order's events are applied by sequence number; one that
                arrives after a later event is Stale.
              </li>
              <li>
                <strong>No lost notifications:</strong> the outbox row commits with the order change, then the
                relay delivers it at least once.
              </li>
            </ul>
            <nav aria-label="Design documents">
              <h3 className="mb-1 font-semibold">Read more</h3>
              <ul className="space-y-1">
                {DOCS.map((doc) => (
                  <li key={doc.href}>
                    <a
                      href={doc.href}
                      className="link inline-flex items-center gap-1"
                      target="_blank"
                      rel="noreferrer"
                    >
                      {doc.label}
                      <ExternalLink aria-hidden="true" className="size-3.5" />
                      <span className="sr-only">(opens in a new tab)</span>
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
