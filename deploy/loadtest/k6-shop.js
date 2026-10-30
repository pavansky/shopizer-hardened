// Mixed storefront load: browse (most traffic), add to cart, and checkout.
// Run against the compose stack:
//   docker run --rm -i --network host -e BASE=http://localhost:8080 grafana/k6:1.3.0 run - < deploy/loadtest/k6-shop.js
// For a realistic catalog, seed it first: scripts/seed-catalog.sh http://localhost:8080
// (1,000 products; the list page then pages over all of them, not over one product).
import http from 'k6/http';
import { check, group } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const API = `${BASE}/api/v1`;
const JSONH = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    browse:   { executor: 'constant-vus', vus: 20, duration: '60s', exec: 'browse' },
    checkout: { executor: 'constant-vus', vus: 4,  duration: '60s', exec: 'checkout', startTime: '5s' },
  },
  thresholds: {
    // listing and category pages: one line each so the summary shows their own p95
    'http_req_duration{name:products}': ['p(95)<1000'],
    'http_req_duration{name:category}': ['p(95)<500'],
    'http_req_duration{name:product}': ['p(95)<500'],
    'http_req_duration{name:checkout}': ['p(95)<1500'],
    'http_req_failed{name:product}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function browse() {
  group('browse', () => {
    check(http.get(`${API}/products?store=DEFAULT&lang=en&count=15`, { tags: { name: 'products' } }), { 'list 200': r => r.status === 200 });
    check(http.get(`${API}/product/oak-bench?store=DEFAULT&lang=en`, { tags: { name: 'product' } }), { 'product 200': r => r.status === 200 });
    check(http.get(`${API}/category/furniture?store=DEFAULT&lang=en`, { tags: { name: 'category' } }), { 'category 200': r => r.status === 200 });
  });
}

const BODY = JSON.stringify({
  customer: { emailAddress: 'load.buyer@example.com', firstName: 'Load', lastName: 'Buyer',
    billing: { firstName: 'Load', lastName: 'Buyer', address: '1 Main St', city: 'Toronto', postalCode: 'M5V2T6', country: 'CA', zone: 'ON', phone: '5555555555' } },
  payment: { paymentType: 'MONEYORDER', transactionType: 'CAPTURE', paymentModule: 'moneyorder', amount: '249.00' },
  currency: 'CAD', customerAgreement: true,
});

export function checkout() {
  const cart = http.post(`${API}/cart`, JSON.stringify({ product: 'oak-bench', quantity: 1 }), Object.assign({ tags: { name: 'cart' } }, JSONH));
  if (cart.status !== 201 && cart.status !== 200) return;
  const code = cart.json('code');
  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const r = http.post(`${API}/cart/${code}/checkout`, BODY,
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { name: 'checkout' } });
  check(r, { 'checkout 200 or 409 (sold out)': x => x.status === 200 || x.status === 409 });
}
