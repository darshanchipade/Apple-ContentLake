# Futuristic Pipeline Design: Image References & Multi-Format Document Support

This document outlines the technical strategy for integrating Images (via references), PDFs, Word documents, and Excel files into the Content Lake pipeline while maximizing the reuse of existing logic and tables.

## 1. Core Data Flow: The "Canonical Normalization" Strategy

To maintain a stable core pipeline, all non-JSON inputs are converted into a **Standardized Internal JSON schema** at the ingestion boundary. This ensures that Cleansing and Enrichment logic remains consistent regardless of the source.

### The Lifecycle of an Asset
1.  **Ingestion**:
    - **Binary Files (PDF, Word, Excel)**: Uploaded to S3; returns a `source_uri`.
    - **Image References**: Received as pointers within a standard JSON payload.
2.  **Normalization (The Adapter Layer)**: Format-specific processors convert the source into a "Virtual JSON".
3.  **Standard Pipeline**: The Virtual JSON is fed into the existing Extraction -> Cleansing -> Enrichment -> Search Indexing flow.

---

## 2. Document-Specific Processing Strategies

### A. PDF Documents
-   **Tooling**: **Amazon Textract** (Recommended) or **Apache PDFBox**.
-   **Extraction Logic**: Textract is superior for preserving layout context (identifying tables, headers, and columns).
-   **Virtual JSON Mapping**:
    - Each Page or Section becomes a JSON object.
    - Page numbers and layout metadata are stored in the **Provenance** field.

### B. Word Documents (DOCX)
-   **Tooling**: **Apache POI**.
-   **Extraction Logic**: Traverse the document's XML structure to extract paragraphs, lists, and tables.
-   **Virtual JSON Mapping**: Maps document headers to `_model` identifiers and text to the `copy` field.

### C. Excel Sheets (XLSX)
-   **Tooling**: **Apache POI**.
-   **Extraction Logic**: Convert rows and columns into Flattened Key-Value pairs.
-   **Metadata**: Sheet names and Cell coordinates are preserved in **Facets** to allow specific chatbot queries like "What is the value in Sheet1 cell B2?".

---

## 3. Cleansing & AI Augmentation

### Image References (AI Generation)
- **Augmentation**: For pointers like `{"image_url": "..."}`, the pipeline triggers **AWS Bedrock (Vision)** to generate descriptions and OCR text.
- **Cleansing**: Normalizes AI-misread characters and standardizes Alt-text length for UI consistency.

---

## 4. Impact on Existing Database Tables

To maintain a unified experience, all metadata is stored in the **existing table structure**:

### A. `enriched_content_element`
-   **Role**: Stores the extracted text from documents or AI-generated image metadata as discrete elements.
-   **Types**: New `item_types` like `PDF_FRAGMENT`, `WORD_PARA`, `EXCEL_CELL`, and `IMAGE_DESCRIPTION`.

### B. `consolidated_enriched_section`
-   **Role**: Acts as the roll-up view for the Search Finder.
-   **Change**: A single entry represents a document "Section" or "Sheet," containing all its constituent text for unified searching.

### C. `content_chunk` & Vector Search
-   **Role**: Powers the Chatbot and Semantic Search.
-   **Change**: Document fragments and image descriptions are chunked and embedded.
-   **Outcome**: The Chatbot can answer questions from a PDF or describe an image reference because they coexist in the same vector space.

---

## 5. Component-Level Changes (Spring Boot)

-   **`NormalizationService`**: A new orchestrator that selects the correct adapter (Textract, POI, Jsoup) based on MIME type.
-   **`AssetRefAdapter`**: Handles the asynchronous lifecycle of Vision AI requests for image pointers.
-   **`UnifiedSearchService`**: Refactored to include document metadata (Authorship, Page Counts) and `asset_reference` pointers in result DTOs.

---

## 6. Implementation Roadmap
-   **Phase 1**: Update Ingestion to support S3-based binary uploads for PDF/Word/Excel.
-   **Phase 2**: Implement the **Canonical Adapters** to transform these binaries into the pipeline's expected JSON format.
-   **Phase 3**: Integrate **Claude 3 Vision** for image reference enrichment.
-   **Phase 4**: Enable **Multimodal Embeddings** for a unified "Chat with anything" experience.
