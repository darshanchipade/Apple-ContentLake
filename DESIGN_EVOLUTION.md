# Futuristic Pipeline Design: From JSON to Multi-Format Assets

This document outlines the strategic evolution of the Content Lake pipeline to support Images, HTML, and Excel formats while maintaining a robust and scalable architecture.

## 1. Core Architecture Evolution: The Canonical Adapter Pattern

To minimize disruption to existing Cleansing and Enrichment logic, we adopt a **Canonical Normalization Strategy**. Every input format is converted into a standard internal JSON schema at the edge of the pipeline.

### High-Level Data Flow
1. **Ingestion**: Receives File (JPG, HTML, XLSX) -> Uploads Binary to S3 -> Returns S3 URI.
2. **Adapter Layer**: format-specific logic (OCR for Images, Jsoup for HTML) generates a "Virtual Content JSON".
3. **Canonicalization**: The Virtual JSON is injected into the standard pipeline.
4. **Standard Pipeline**: Extraction -> Cleansing -> Enrichment -> Search Indexing.

---

## 2. Component-Level Changes

### A. Spring Boot Service Layer
- **`SourceAdapter` Interface**: Define `extractToCanonicalJson(S3Object object)`.
- **`ImageAdapter`**: Integrates with **AWS Bedrock (Vision)** or **AWS Rekognition**.
    - *Input*: Image Binary.
    - *Output*: `{"description": "...", "ocr_text": "...", "detected_objects": [...]}`.
- **`HtmlAdapter`**: Uses **Jsoup**.
    - *Input*: HTML Source.
    - *Output*: Structured text fragments keyed by CSS selectors or semantic tags.
- **`ExcelAdapter`**: Uses **Apache POI**.
    - *Input*: XLSX.
    - *Output*: Flattened key-value pairs representing rows and columns.

### B. Database Schema (YugabyteDB / Postgres)
- **`asset_metadata` (New Table)**:
    - `id` (UUID)
    - `source_uri` (S3 path)
    - `mime_type` (image/jpeg, text/html, etc.)
    - `file_hash` (SHA-256 for deduplication)
    - `dimensions` (For images)
- **`raw_data_store`**: Add `asset_id` FK to link back to the binary source.

---

## 3. Storage Strategy: The "Shadow Lake"
We do not store binary assets in the transactional database.
1. **Binary Storage**: Amazon S3 (Primary storage).
2. **Metadata Storage**: Database (Stores S3 pointers and AI-generated descriptions).
3. **Search Index**: OpenSearch/Vector DB (Stores embeddings of the *descriptions* for semantic search).

---

## 4. Futuristic Enhancements

### 1. Semantic Search (Vector Embeddings)
- **Problem**: Keyword search fails if an image description says "Futuristic headset" but the user searches for "Apple VR".
- **Solution**: Use **Amazon Titan Multimodal Embeddings**. Generate vectors for both text and images. Store in `pgvector`.
- **Outcome**: The Chatbot can "see" images by comparing the search vector to the image description vector.

### 2. Event-Driven Scalability
- Use **AWS SQS** (already partially implemented) to decouple Ingestion from AI Processing.
- Processing a 50MB Excel sheet or 100 high-res images should happen in the background, updating the UI via WebSockets or polling.

### 3. Smart Deduplication (Content-Addressable)
- Use **Perceptual Hashing (pHash)** for images.
- If a user uploads a slightly cropped version of an existing image, the system identifies it as a duplicate and reuses existing enrichment data, saving AI costs.

---

## 5. Implementation Roadmap
1. **Phase 1**: Refactor `DataIngestionService` to use a Strategy pattern for format detection.
2. **Phase 2**: Implement `S3StorageService` for binary persistence.
3. **Phase 3**: Integrate **Claude 3 Vision** (via Bedrock) for image-to-JSON conversion.
4. **Phase 4**: Add `pgvector` support to the Search Controller for multimodal retrieval.
