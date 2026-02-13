# Futuristic Pipeline Design: Image References & Multi-Format Assets

This document outlines the technical strategy for integrating Images (via references), HTML, and Excel files into the Content Lake pipeline while maximizing the reuse of existing logic and tables.

## 1. Core Data Flow: The "Virtual JSON" Augmentation

The pipeline's input remains a **JSON payload**. When images are involved, the user provides a JSON containing **Image References** (External URLs or Asset IDs), not binary files.

### The Lifecycle of an Image Asset
1.  **Ingestion (Input JSON)**: The user sends a standard JSON containing an external pointer: `{"image_url": "https://cdn.com/asset1.jpg"}`.
2.  **Augmentation (AI Generation)**: The pipeline detects the `image_url`, triggers **AWS Bedrock (Vision)**, and retrieves a descriptive metadata object: `{"description": "A silver laptop on a desk", "ocr_text": "Buy Now"}`.
3.  **Standardization (Virtual JSON)**: The generated metadata is injected back into the JSON structure as if it were original text content.
4.  **Cleansing**: The AI-generated text (`description`, `ocr_text`) is run through the cleansing engine to normalize whitespace, remove unwanted tokens, and ensure it meets system standards.

---

## 2. Cleansing Strategy for Non-Text Assets

Cleansing for image-derived data focuses on **AI Output Sanitization**:
-   **OCR Text Normalization**: AI-extracted text from images often contains erratic formatting or misread characters. Cleansing rules will normalize these into readable strings.
-   **Alt-Text Standardization**: Descriptions are capped and formatted to match existing copy elements for UI consistency.
-   **Pointer Validation**: The Cleansing stage verifies that the `image_url` is still accessible and follows the required security protocols (HTTPS, specific CDN domains).

---

## 3. Impact on Existing Database Tables

To maintain a unified search and chatbot experience, image metadata is stored in the **existing table structure**:

### A. `enriched_content_element`
-   **Role**: Stores the AI-generated metadata as discrete elements.
-   **Change**: A new `item_type` (e.g., `IMAGE_DESCRIPTION`, `IMAGE_OCR`) is added.
-   **Relationship**: These elements will link to a new `asset_references` table via a foreign key, allowing the UI to display the original image next to its enrichment.

### B. `consolidated_enriched_section`
-   **Role**: Acts as the roll-up view for the Search Finder.
-   **Change**: The "full content" field for a section will now include the image descriptions and OCR text.
-   **Benefit**: When a user searches for "silver laptop," the Search Finder finds the section because the image description is consolidated into the section's searchable text.

### C. `content_chunk` & Vector Search
-   **Role**: Powers semantic search and the Chatbot.
-   **Change**: Image descriptions are chunked and embedded just like text.
-   **Outcome**: The Chatbot can "see" assets. A query like "Show me visual assets related to portabilty" will return images where the AI-generated description contains portabilty-related concepts.

---

## 4. Component-Level Changes (Spring Boot)

-   **`AssetRefAdapter`**: A new service to parse JSON for image pointers and handle the asynchronous lifecycle of Vision AI requests.
-   **`UnifiedCleansingService`**: Updated to handle both "Original Field" text and "AI Derived" text using the same regex-based sanitization logic.
-   **`SearchService`**: Refactored to include `asset_reference` metadata in result DTOs so the frontend can render image previews.

---

## 5. Summary of Implementation Roadmap
-   **Short Term**: Refactor Ingestion to recognize `image_url` keys and map them to the `asset_references` table.
-   **Medium Term**: Trigger Bedrock Vision on-the-fly and feed the output into the existing `CleansingProcessor`.
-   **Long Term**: Use Titan Multimodal Embeddings to allow unified Chatbot responses across text and visuals.
