# Regole per gli agent

## Code review

- Per qualsiasi richiesta di **code review**, in base alla tipologia di review (scope) avvia uno dei Reviewer Agent con lo scope più coerente con la richiesta.
- In assenza nella richiesta di un identificativo dello scope, blocca l'esecuzione e segnala l'errore richiedendo maggiori dettagli.
  - ES: "review richiesta senza scope chiaro, su quale ambito eseguire la review?"
- In caso di uno scope non presente in uno dei reviewer, blocca l'esecuzione e segnala l'errore richiedendo maggiori dettagli.
  - ES: "review richiesta fuori scope, indicare uno scope valido"
- Se la richiesta contiene due o più scope, blocca l'esecuzione e segnala l'errore richiedendo maggiori dettagli.
  - ES: "review richiesta con scope troppo ampio, indica uno scope più preciso"
- Non avviare due o più reviewer, nemmeno se uno è in primo piano e l'altro in background, e non aggiungere altri reviewer dopo quello selezionato. Le attivazioni devono essere sempre mutuamente esclusive.
- Alla fine della review crea un file con nome **`report_review_<YYYY-MM-DD>.md`** (Data odierna locale) salvandolo nella directory `test/`
    - In caso di presenza di un file con quel nome, crea un secondo file aggiungendo un numero alla fine in modo progressivo (es: "report_review_2026-09-24_2" e "report_review_2026-09-24_3")
    - Non sovrascrivere, cancellare o modificare per nessun motivo un report già esistente, crea sempre un nuovo report con la nomenclatura spiegata precedentemente.