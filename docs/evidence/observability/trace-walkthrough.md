# Quote-to-fulfillment trace walkthrough

The acceptance browser test sends the synthetic correlation ID `74000000-0000-4000-8000-000000000013` on the customer acceptance request. It verifies the response header, then the E2E script proves that exactly one `QuotationAcceptedV1` publication retains both that correlation ID and a valid W3C `traceparent` value.

In the full profile, the same journey is inspectable as follows:

1. Start the profile with replaced local secrets: `make dev-full`.
2. Run `make acceptance-e2e` or complete the documented quotation journey in the browser.
3. Open Grafana at `http://localhost:3000`, select **Explore**, then the **Tempo** datasource.
4. Search `service.name = cellarbridge-backend` and narrow by span attribute `cellarbridge.correlation_id` when viewing event-consumer spans.
5. Follow the HTTP acceptance span to `cellarbridge.event.publish`; the following asynchronous consumer span is a separate root with a span link to the producer context. This preserves the real asynchronous timing model.
6. Use the audit page correlation filter to cross-check business evidence. Audit correlation and trace identity are related diagnostic keys but are not interchangeable.

Expected span boundaries are HTTP, reliable event publish/database insert, local event consume, scheduler work and simulated fulfillment adapter. Expected event span attributes include event/correlation/causation identity; these high-cardinality values do not appear as metric labels.

HTTP traces retain the normalized route template but omit the raw request URL. This is required because customer portal capability tokens are carried in the path.

The checked browser/database assertion is in `scripts/customer_quotation_acceptance_e2e.sh`. Tempo is an optional diagnostic dependency: trace export failure must not fail or roll back the business transaction.
