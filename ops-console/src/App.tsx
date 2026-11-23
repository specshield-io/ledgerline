import { Activity } from "lucide-react";
import { NavLink, Route, Routes } from "react-router";
import { Banner } from "./components/Banner";
import { HowItWorks } from "./components/HowItWorks";
import { LoginButton } from "./components/LoginDialog";
import { MetricsStrip } from "./components/MetricsStrip";
import { OrderDrawer } from "./components/OrderDrawer";
import { OrdersPage } from "./components/OrdersPage";
import { OutboxPage } from "./components/OutboxPage";
import { ScenariosPanel } from "./components/ScenariosPanel";

const tab = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium focus-ring ${
    isActive
      ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
      : "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"
  }`;

export function App() {
  return (
    <>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <Banner />
      <header className="border-b border-zinc-200 dark:border-zinc-800">
        <div className="mx-auto flex max-w-screen-2xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <h1 className="flex items-center gap-2 text-lg font-semibold">
            <Activity aria-hidden="true" className="size-5 text-sky-700 dark:text-sky-400" />
            Ledgerline operations
          </h1>
          <nav aria-label="Sections" className="flex gap-1">
            <NavLink to="/" end className={tab}>
              Orders
            </NavLink>
            <NavLink to="/outbox" className={tab}>
              Outbox
            </NavLink>
          </nav>
          <div className="flex flex-wrap items-center gap-2">
            <HowItWorks />
            <LoginButton />
          </div>
        </div>
      </header>
      <MetricsStrip />
      <main
        id="main"
        className="mx-auto grid max-w-screen-2xl gap-6 px-4 py-4 lg:grid-cols-[18rem_minmax(0,1fr)]"
      >
        <aside className="lg:order-first">
          <ScenariosPanel />
        </aside>
        <div className="min-w-0">
          <Routes>
            <Route path="/" element={<OrdersPage />} />
            <Route path="/outbox" element={<OutboxPage />} />
            <Route path="*" element={<OrdersPage />} />
          </Routes>
        </div>
      </main>
      <OrderDrawer />
    </>
  );
}
