# ops-console

The Ledgerline operations console: run the chaos scenarios and watch, live, how the service handles them.
SpecShield Labs internal demonstration, synthetic data only.

React 19 + TypeScript (strict) + Vite, TanStack Query, React Router, Tailwind CSS 4, Radix UI primitives, lucide icons.
API types are generated from [`../docs/openapi.json`](../docs/openapi.json) with openapi-typescript and used through
openapi-fetch. The Maven build (`./mvnw package`) builds this app and serves it from the Spring Boot jar, so one
container serves both.

## Develop

Node 22.23.4 and npm 11.21.2 (npm 10 cannot resolve this dependency tree). After any Maven build they are in
`node/`: `export PATH=$PWD/node:$PATH`.

```bash
npm ci
npm run dev            # http://localhost:5173, proxies /api to http://localhost:8080 (LEDGERLINE_API to change)
npm run generate:api   # after an API change: regenerate src/api/schema.d.ts from ../docs/openapi.json
```

## Test

| Command                 | What                                                                       |
| ----------------------- | -------------------------------------------------------------------------- |
| `npm run lint`          | ESLint + Prettier check                                                    |
| `npm run typecheck`     | `tsc --noEmit`                                                             |
| `npm test`              | Vitest + Testing Library + MSW (LL-F01..LL-F05 and more)                   |
| `npm run build`         | Production build into `dist/`                                              |
| `npx playwright test`   | End-to-end (LL-E01..LL-E07) against a running stack, below                 |
| `scripts/e2e-docker.sh` | The same suite in the pinned Linux Playwright image, exactly as CI runs it |

End-to-end tests need the stack with short outbox retries (`docker-compose.e2e.yml`), from the repository root:

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up --build --wait
cd ops-console && npx playwright test     # E2E_BASE_URL defaults to http://localhost:8080
```

`E2E_ADMIN_USER` / `E2E_ADMIN_PASSWORD` default to the local profile's synthetic ADMIN account. The suite is serial
(one backend, a 3-run chaos limit) and takes about 30 s. LL-E07 compares phone-sized screenshots; baselines are per
platform (`e2e/mobile.spec.ts-snapshots/*-darwin.png`, `*-linux.png`). Update the Linux ones only through
`scripts/e2e-docker.sh --project=mobile --no-deps --update-snapshots`, so they match CI.

## Notes

- **Auth**: HTTP Basic against `GET /api/v1/admin/me`; credentials live in memory only (a reload signs out). Requests
  send `X-Requested-With: XMLHttpRequest`, so a failed sign-in never triggers the browser's own login prompt.
- **Polling**: timeline every 1 s while a scenario runs (else 5 s), deliveries and run status every 2 s, orders and
  stats every 5 s: about 150 requests a minute at most, under the 240/min public limit.
- **Video link** in the banner: set `VITE_DEMO_VIDEO_URL` at build time; hidden until a video exists.
- **npm overrides**: `minimatch` 11.0.5 for ESLint/typescript-eslint (fixes a moderate ReDoS advisory; upstream made
  the same change in releases newer than the two-week pin rule), and a single `playwright-core` 1.65.0 for
  `@axe-core/playwright`.
