# 🏥 ECF Enrichment Processor (Edifecs Internal Component)

This repository contains a core component used in a production healthcare data processing pipeline at Edifecs. It enriches the **Edifecs Common Format (ECF)** model with data from external Web Service (WS) responses before sending it further down the transformation route.

---

## 📌 Overview

This processor operates within Edifecs’ **Enrollment processing** route, receiving input from multiple sources, including:
- PolicyUnit XML messages
- External Web Service (WS) call responses (in JSON or XML)

It then:
- **Parses** the response
- **Identifies message types**
- **Matches and enriches** the appropriate `PolicyUnit`
- **Attaches error info** if enrichment fails
- **Sends the final enriched ECF model** downstream via the messaging framework

## ⚙️ Sample Flow (Simplified)
Incoming Message Stream
│

├─> Parse Headers

├─> Identify PolicyUnit & WS Response

├─> Deserialize WS Response

├─> Enrich ECF/PolicyUnit object

├─> Add ErrorInfo (if any issues)

└─> Output Enriched PolicyUnit


---

## 📌 Why This Matters

This is **production code** that plays a key role in enriching US Healthcare data (834 Enrollment and Coverage formats). It directly impacts **claims processing**, **eligibility reporting**, and **payer-patient interaction timelines**.

---

## 🔐 Note

Due to internal dependencies (`UCFWriter`, `PolicyUnit`, `IProcessingContext`), this repo serves as a **code showcase** and **not a standalone tool.

