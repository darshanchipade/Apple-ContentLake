# Futuristic Pipeline Design: From JSON to Multi-Format Assets

This document outlines the strategic evolution of the Content Lake pipeline to support Images (via references), HTML, and Excel formats while maintaining a robust and scalable architecture.

## 1. Core Architecture Evolution: The Canonical Adapter Pattern

To minimize disruption to existing Cleansing and Enrichment logic, we adopt a **Canonical Normalization Strategy**. Every input format is converted into a standard internal JSON schema at the edge of the pipeline.

### High-Level Data Flow
1. **Ingestion**:
   - **JSON/API**: Receives payload containing text content and/or **Image References** (External URLs, Asset IDs).
   - **Files (HTML/XLSX)**: Binary is uploaded to S3 -> Returns S3 URI.
2. **Adapter Layer**: format-specific logic generates a "Virtual Content JSON".
   - **Image Reference**: Pipeline detects an image URL/ID and makes an asynchronous AI call to generate metadata.
   - **HTML/XLSX**: Parsers convert structural data into structured fragments.
3. **Canonicalization**: The Virtual JSON is injected into the standard pipeline.
4. **Standard Pipeline**: Extraction -> Cleansing -> Enrichment -> Search Indexing.

---

## 2. Component-Level Changes

### A. Spring Boot Service Layer
- **`SourceAdapter` Interface**: Define `extractToCanonicalJson(InputSource source)`.
- **`ImageReferenceAdapter`**:
    - *Input*: External Image URL or Asset Metadata from JSON.
    - *Action*: Triggers **AWS Bedrock (Claude 3 Vision)** using the external URL.
    - *Output*: `{"image_description": "...", "ocr_text": "...", "asset_id": "..."}`.
- **`HtmlAdapter`**: Uses **Jsoup**.
    - *Input*: HTML Source.
    - *Output*: Structured text fragments keyed by CSS selectors or semantic tags.
- **`ExcelAdapter`**: Uses **Apache POI**.
    - *Input*: XLSX.
    - *Output*: Flattened key-value pairs representing rows and columns.

### B. Database Schema (YugabyteDB / Postgres)
- **`asset_references` (New Table)**:
    - `id` (UUID)
    - `external_url` (Pointer to the asset's actual location)
    - `asset_id` (Original system identifier)
    - `mime_type` (image/jpeg, etc.)
    - `ai_metadata` (JSONB storing description, OCR, dimensions)
- **`cleansed_item_detail`**: Link back to `asset_references` when a field is derived from an image.

---

## 3. Storage & Integration Strategy
1. **Actual Assets**: Remain at their original location (CDN, External DAM, etc.). The pipeline only handles **Metadata and Pointers**.
2. **Metadata Enrichment**: AI insights (descriptions, OCR) are stored in the Database and indexed for search.
3. **Search Index**: OpenSearch/Vector DB stores embeddings of the *image descriptions* to enable semantic discovery.

---

## 4. Futuristic Enhancements

### 1. Semantic Search (Vector Embeddings)
- **Solution**: Use **Amazon Titan Multimodal Embeddings**. Generate vectors for both text and image descriptions. Store in `pgvector`.
- **Outcome**: The Search Finder and Chatbot can find visual assets by searching for their "meaning" (e.g., searching for "wireless technology" finds images containing headsets).

### 2. Event-Driven AI Processing
- For image references, the pipeline should not block. It emits an **SQS message** for the Bedrock worker to process the external image and update the enrichment context once ready.

### 3. Cross-Format Deduplication
- Use **Asset IDs** or **URL Hashing** to detect if an image reference has been processed before, reusing previous AI insights to reduce costs and latency.

---

## 5. Implementation Roadmap
1. **Phase 1**: Update `DataIngestionService` to identify image-related nodes in JSON payloads.
2. **Phase 2**: Implement `ImageReferenceAdapter` to call Bedrock Vision using external URLs.
3. **Phase 3**: Refactor `SearchController` to query both text elements and image metadata.
4. **Phase 4**: Transition to Multimodal Embeddings for a unified "Chat with Assets" experience.
