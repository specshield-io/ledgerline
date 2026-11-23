import { Toast } from "radix-ui";
import { CircleCheck, CircleX, Info, X } from "lucide-react";
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";

type Variant = "success" | "error" | "info";

interface ToastMessage {
  id: number;
  title: string;
  description?: string;
  variant: Variant;
}

type Push = (toast: Omit<ToastMessage, "id">) => void;

const ToastContext = createContext<Push | null>(null);

const STYLE: Record<Variant, { icon: typeof Info; className: string }> = {
  success: { icon: CircleCheck, className: "border-emerald-400 dark:border-emerald-700" },
  error: { icon: CircleX, className: "border-rose-400 dark:border-rose-700" },
  info: { icon: Info, className: "border-sky-400 dark:border-sky-700" },
};

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const push = useCallback<Push>((toast) => {
    setToasts((current) => [...current.slice(-3), { ...toast, id: nextId++ }]);
  }, []);
  const remove = (id: number) => setToasts((current) => current.filter((t) => t.id !== id));

  return (
    <ToastContext.Provider value={useMemo(() => push, [push])}>
      <Toast.Provider swipeDirection="right" duration={6_000}>
        {children}
        {toasts.map((toast) => {
          const { icon: Icon, className } = STYLE[toast.variant];
          return (
            <Toast.Root
              key={toast.id}
              type={toast.variant === "error" ? "foreground" : "background"}
              onOpenChange={(open) => !open && remove(toast.id)}
              className={`flex items-start gap-3 rounded-md border-l-4 bg-white p-3 text-sm shadow-lg ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-700 ${className}`}
            >
              <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <div className="min-w-0 flex-1">
                <Toast.Title className="font-semibold">{toast.title}</Toast.Title>
                {toast.description && (
                  <Toast.Description className="mt-0.5 text-zinc-700 dark:text-zinc-300">
                    {toast.description}
                  </Toast.Description>
                )}
              </div>
              <Toast.Close aria-label="Dismiss notification" className="rounded p-0.5 focus-ring">
                <X aria-hidden="true" className="size-4" />
              </Toast.Close>
            </Toast.Root>
          );
        })}
        <Toast.Viewport className="fixed right-0 bottom-0 z-50 flex w-full max-w-sm flex-col gap-2 p-4 outline-none" />
      </Toast.Provider>
    </ToastContext.Provider>
  );
}

export function useToast(): Push {
  const push = useContext(ToastContext);
  if (!push) throw new Error("useToast outside ToastProvider");
  return push;
}
