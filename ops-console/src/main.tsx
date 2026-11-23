import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import { Providers } from "./Providers";
import { createQueryClient } from "./queryClient";
import "./index.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root element");

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <Providers client={createQueryClient()}>
        <App />
      </Providers>
    </BrowserRouter>
  </StrictMode>,
);
