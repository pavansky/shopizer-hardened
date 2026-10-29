# Demo stack runbook

One command brings up the whole platform on a laptop, with no cloud account and no cost.

| Service | URL | What it shows |
|---|---|---|
| Storefront | http://localhost:3000 | Browse, cart, guest checkout |
| Admin | http://localhost:4200 | Orders, payment statuses, refunds |
| API | http://localhost:8080/swagger-ui.html | The REST API |
| Grafana | http://localhost:3001 | Checkout latency, reconciler outcomes, DB pool |
| Prometheus | http://localhost:9090/alerts | Payment alert rules |
| Mailpit | http://localhost:8025 | Order confirmation emails, caught locally |

## 1. Set up once

Clone the three repositories side by side.

```bash
git clone https://github.com/pavansky/shopizer-hardened.git
```

```bash
git clone https://github.com/pavansky/shopizer-admin-hardened.git
```

```bash
git clone https://github.com/pavansky/shopizer-shop-hardened.git
```

Create the secrets file and change every value.

```bash
cp shopizer-hardened/deploy/.env.example shopizer-hardened/deploy/.env
```

## 2. Start

```bash
cd shopizer-hardened && docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

The first build takes about 10 minutes, because it compiles the API and both frontends. Later starts take under a minute. Wait until the API is healthy.

```bash
docker compose -f deploy/docker-compose.yml ps
```

## 3. Seed and run the scripted demo

```bash
scripts/demo-checkout.sh http://localhost:8080
```

It creates a category and a product, then shows each fix in turn:

- the security defaults
- an idempotent checkout retry
- partial, over-limit and full refunds
- the final order status

## 4. Show the failure handling live

**Crash after the gateway charged.** Simulate an order stuck in `PAYMENT_PENDING` with an unresolved payment attempt, and watch the reconciler move it to `PAYMENT_REVIEW` instead of failing it.

```bash
scripts/simulate-stuck-order.sh 1
```

Then open Grafana, where the reconciler outcome panel shows `review`. Prometheus shows the `OrdersNeedPaymentReview` alert firing.

**Backups.** The `backup` service writes a checksummed dump every six hours. Take one now and prove it restores.

```bash
docker compose -f deploy/docker-compose.yml restart backup
```

```bash
deploy/backup/restore-drill.sh
```

The drill restores into a scratch MySQL and compares row counts with the live database.

## 5. If something goes wrong on the day

| Symptom | Fix |
|---|---|
| API unhealthy after start | `docker compose -f deploy/docker-compose.yml logs api` usually shows a wrong DB password in `.env` |
| Port already in use | Change `API_PORT`, `ADMIN_PORT` or `STOREFRONT_PORT` in `.env` |
| Need a clean slate | `docker compose -f deploy/docker-compose.yml down -v` removes containers and data |

## 6. Stop

```bash
docker compose -f deploy/docker-compose.yml down
```

The data volume is kept. Add `-v` to delete it.
