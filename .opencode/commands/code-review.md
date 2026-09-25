---
description: "Avvia una code review con l'agente primario read-only code-reviewer-banking-domain"
agent: code-reviewer-banking-domain
subagent: false
---

Esegui una code review con l'argomento di attivazione obbligatorio `scope=banking-domain` su $ARGUMENTS, applicando il tuo protocollo da agente READ-ONLY. Se $ARGUMENTS contiene anche `scope=amount-validation` oppure `scope=external-api`, non iniziare la review e chiedi di scegliere un solo scope. Non avviare altri reviewer. Riporta i rilievi per severità con riferimenti a file e righe.

Al termine della review, esegui anche il check AML (skill `compliance-aml-check`) e salva il report finale con la convenzione di progetto: `test/report_review+aml_<YYYY-MM-DD>.md` (data odierna locale), chiamando lo strumento `review_save_review_report` oppure scrivendo il file con `write`. Verifica che il file del giorno esista e non sia duplicato.