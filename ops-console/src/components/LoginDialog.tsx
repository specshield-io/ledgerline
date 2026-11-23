import { Dialog } from "radix-ui";
import { LogIn, LogOut, X } from "lucide-react";
import { useId, useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import { useAuth } from "../state/auth";

export function LoginButton() {
  const { session, signIn, signOut } = useAuth();
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const userId = useId();
  const passwordId = useId();
  const errorId = useId();

  if (session) {
    return (
      <div className="flex items-center gap-2 text-sm">
        <span className="hidden sm:inline">
          {session.username}{" "}
          <span className="text-zinc-600 dark:text-zinc-400">({session.roles.join(", ")})</span>
        </span>
        <button type="button" className="btn" onClick={signOut}>
          <LogOut aria-hidden="true" className="size-4" />
          Sign out
        </button>
      </div>
    );
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      await signIn({ username: String(form.get("username")), password: String(form.get("password")) });
      setOpen(false);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? "Wrong username or password."
          : e instanceof ApiError && e.status === 429
            ? "Too many requests. Wait a minute and try again."
            : "Could not sign in. The API may be unreachable.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        setError(null);
      }}
    >
      <Dialog.Trigger asChild>
        <button type="button" className="btn">
          <LogIn aria-hidden="true" className="size-4" />
          Sign in
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-zinc-950/40" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[calc(100%-2rem)] max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-lg bg-white p-5 shadow-xl dark:bg-zinc-900">
          <div className="flex items-start justify-between">
            <Dialog.Title className="text-base font-semibold">Operator sign-in</Dialog.Title>
            <Dialog.Close aria-label="Close" className="btn-icon">
              <X aria-hidden="true" className="size-4" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-1 text-sm text-zinc-700 dark:text-zinc-300">
            VIEWER accounts see the outbox; ADMIN accounts can also replay dead letters. Credentials are kept
            in this tab's memory only.
          </Dialog.Description>
          <form onSubmit={submit} className="mt-4 space-y-3" aria-describedby={error ? errorId : undefined}>
            <div className="space-y-1">
              <label htmlFor={userId} className="block text-sm font-medium">
                Username
              </label>
              <input id={userId} name="username" autoComplete="username" required className="field w-full" />
            </div>
            <div className="space-y-1">
              <label htmlFor={passwordId} className="block text-sm font-medium">
                Password
              </label>
              <input
                id={passwordId}
                name="password"
                type="password"
                autoComplete="current-password"
                required
                className="field w-full"
              />
            </div>
            {error && (
              <p id={errorId} role="alert" className="text-sm font-medium text-rose-800 dark:text-rose-300">
                {error}
              </p>
            )}
            <button type="submit" className="btn btn-primary w-full justify-center" disabled={busy}>
              {busy ? "Signing in…" : "Sign in"}
            </button>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
