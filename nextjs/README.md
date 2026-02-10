This UI is built with [Next.js](https://nextjs.org) and proxies ingestion
requests to the companion Spring Boot service in
[`darshanchipade/springboot-SQS-Impl`](https://github.com/darshanchipade/springboot-SQS-Impl).

## Getting Started

Install dependencies and run the dev server:

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) to view the experience.

## Spring Boot Integration

The `/api/ingestion/*` routes call directly into the Spring Boot
`DataExtractionController`. Configure the backend location through
`.env.local`:

```bash
SPRINGBOOT_BASE_URL=http://localhost:8081
```

Available ingestion modes:

- **Local upload** – `POST /api/extract-cleanse-enrich-and-store` with a JSON file.
- **S3/classpath ingestion** – `GET /api/extract-cleanse-enrich-and-store?sourceUri=...`
  (accepts `s3://` and `classpath:` URIs reachable by the backend).
- **API payload** – `POST /api/ingest-json-payload` with a JSON payload body.
- **Status checks** – `GET /api/cleansed-data-status/{id}`.

Ensure the Spring Boot app has credentials for any referenced S3 buckets
before using the S3/classpath tab.

## Notes

- `app/page.tsx` renders the entire workflow.
- `/src/app/api/ingestion/*` contains thin proxy handlers to the Spring Boot service.
- Heroicons power the UI icons (`@heroicons/react`).
