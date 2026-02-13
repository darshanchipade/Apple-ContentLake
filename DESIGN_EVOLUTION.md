# Futuristic Pipeline Design: Image References & Multi-Format Support

This document outlines the technical strategy for integrating Images (via references), PDFs, Word documents, Excel files, and **HTML sources** into the Content Lake pipeline while maximizing the reuse of existing logic and tables.

## 1. Core Data Flow: The "Canonical Normalization" Strategy

To maintain a stable core pipeline, all non-JSON inputs are converted into a **Standardized Internal JSON schema** at the ingestion boundary. This ensures that Cleansing and Enrichment logic remains consistent regardless of the source.

### The Lifecycle of an Asset
1.  **Ingestion**:
    - **Binary Files (PDF, Word, Excel, HTML)**: Uploaded to S3; returns a `source_uri`.
    - **Web Sources**: URL is provided for crawling/scraping.
    - **Image References**: Received as pointers within a standard JSON payload.
2.  **Normalization (The Adapter Layer)**: Format-specific processors convert the source into a "Virtual JSON".
3.  **Standard Pipeline**: The Virtual JSON is fed into the existing Extraction -> Cleansing -> Enrichment -> Search Indexing flow.

---

## 2. Document & HTML Processing Strategies

### A. HTML Sources (Websites & Static Files)
-   **Tooling**: **Jsoup** (Java HTML Parser).
-   **Ingestion Flow**:
    - **File Upload**: Processing a static `.html` or `.zip` of a site.
    - **URL Scraping**: Providing a URL which the pipeline fetches and parses.
-   **Extraction Logic**:
    - **Selectors to Paths**: Use CSS selectors or XPath to map DOM elements to `_path`. For example, a `<div id="hero-title">` maps to `hero-title`.
    - **Semantic Mapping**: Map standard tags (`<h1>`, `<article>`, `<meta name="description">`) to pipeline `_model` identifiers.
    - **Asset Discovery**: Identify `<img>` tags and `<a>` links. Images are emitted as downstream "Image References" for Vision AI processing.
-   **Virtual JSON Mapping**: The HTML hierarchy is flattened into a JSON object where keys represent the semantic location and values contain the `copy`.

### B. PDF Documents
-   **Tooling**: **Amazon Textract** (Recommended) for layout-aware extraction.
-   **Extraction Logic**: Identifying tables, headers, and reading order.
-   **Virtual JSON Mapping**: Pages/Sections become JSON objects with text fragments.

### C. Word (DOCX) & Excel (XLSX)
-   **Tooling**: **Apache POI**.
-   **Logic**: Converting paragraphs (Word) or cells (Excel) into structured key-value pairs, preserving authorship and sheet context in **Facets**.

---

## 3. Cleansing & AI Augmentation

### Image References (Vision AI)
- **Action**: For pointers discovered in JSON or HTML (e.g., `<img src="...">`), the pipeline triggers **AWS Bedrock (Vision)**.
- **Normalization**: AI-generated Alt-text and OCR are sanitized via standard cleansing rules (whitespace normalization, sensitive token scrubbing).

---

## 4. Impact on Existing Database Tables

### A. `enriched_content_element`
-   **Types**: New types like `HTML_META`, `HTML_BODY`, `PDF_TABLE`, `IMAGE_ALT`.
-   **Selectors**: For HTML, the original CSS selector can be stored in the metadata to allow "Source-to-Lake" traceability.

### B. `consolidated_enriched_section`
-   **Unit of Consolidation**: For HTML, a "Section" might represent a single webpage. For Excel, it represents a Sheet.
-   **Unified Search**: Enables the Search Finder to locate a "Page" or "Document" based on any text fragment within it.

### C. `content_chunk` & Vector Search
-   **Chunking**: HTML is chunked by block-level elements (div, p, section).
-   **Semantic Querying**: The Chatbot can answer questions about website content by retrieving the relevant HTML fragments from the vector store.

---

## 5. Component-Level Changes (Spring Boot)

-   **`HtmlAdapter`**: A dedicated service using Jsoup to clean HTML (removing scripts/styles) and extract structured content.
-   **`MetadataHarvester`**: Extracts technical metadata from documents (File size, Author, Last Modified, HTML Meta tags) and stores them in `Provenance`.
-   **`CrawlController`**: (Optional) New endpoint to initiate content extraction from a live URL.

---

## 6. Implementation Roadmap
-   **Phase 1**: Support S3-based binary uploads for PDF/Word/Excel/HTML.
-   **Phase 2**: Implement **Jsoup Adapter** for HTML structure-to-JSON mapping.
-   **Phase 3**: Integrate **Claude 3 Vision** for image reference enrichment found in HTML/JSON.
-   **Phase 4**: Enable **Multimodal Embeddings** for a unified "Chat with anything" experience.
