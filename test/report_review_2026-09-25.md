# LipariBank — Code review specialistica delle API esterne

- **Data:** 2026-09-25
- **Ambiente:** Spring Boot 3.3.4 / Java 21
- **Scope unico:** API esterne — client HTTP, contratti, autenticazione, TLS, timeout, retry, circuit breaker, gestione errori e propagazione dei contesti
- **Esito:** 1 finding confermato, 1 rischio condizionale da validare prima di collegare il flusso a un partner esterno
- **Modalità:** review read-only del codice; nessun altro reviewer avviato e nessun servizio reale invocato

## Sintesi

Nel codice sorgente non sono presenti client outbound HTTP (`RestClient`, `RestTemplate`, `WebClient`, Java/Apache HTTP client), client Feign o altri adapter gRPC/WebSocket. Non risultano neppure dipendenze o proprietà di configurazione per un client esterno. Di conseguenza, controlli come base URL, timeout, redirect, TLS, retry, circuit breaker/bulkhead, mapping degli status e limiti di risposta non sono applicabili all'attuale implementazione e non sono riportati come finding.

È però presente un componente di firma destinata a un partner esterno: `TransferExecutionService`. Esso contiene una credenzione di firma hardcoded e produce un `partnerSignature`. Questo costituisce un problema concreto di sicurezza nel confine di fiducia verso il partner, indipendentemente dall'assenza, allo stato attuale, di una chiamata HTTP in uscita.

## Finding e rischi per severità

### [CRITICAL] Credenziale di firma del partner hardcoded nel sorgente e nel JAR — CONFERMATO

- **Riferimenti:**
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:27` — segreto di firma con prefisso `live` assegnato a una costante `static final`.
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:100-107` — il segreto viene usato direttamente per inizializzare HMAC-SHA256.
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:62-65` — la firma viene inclusa nel risultato restituito al chiamante.
  - `target/liparibank-spring-target-1.0.0.jar` — la classe è presente nel pacchetto applicativo; il valore è quindi riproducibile dal bytecode, non soltanto dal sorgente.
- **Flusso/scenario concreto:** chiunque ottenga il repository o l'artefatto può estrarre la chiave e calcolare una firma HMAC valida per un `operationId` scelto. Se il partner considera questa firma una prova di integrità o autenticità di una notifica di trasferimento, un attaccante può produrre dati firmati senza possedere il materiale crittografico legittimo. La validità del segreto su un ambiente reale non può essere verificata dal repository, ma l'esposizione del materiale nel codice è confermata.
- **Impatto:** potenziale falsificazione di notifiche o risposte partner associate a trasferimenti, con conseguenze sulla correttezza del circuito di pagamento/regolamento in funzione delle regole di verifica del partner. La rotazione richiede inoltre una nuova build e una sostituzione coordinata, aumentando il rischio di continued use di credenziali compromesse.
- **Correzione proposta:** revocare e ruotare immediatamente il valore esposto; recuperarlo dal secret manager oppure da una variabile d'ambiente iniettata senza default; far fallire l'avvio se la chiave non è presente; utilizzare chiavi distinte per ambiente e, se supportato dal partner, un `keyId` per la rotazione. Aggiungere scansione dei segreti nel repository/CI e verificare che chiavi, payload autentici e credenziali non compaiano nei log o nelle eccezioni.

### [MEDIUM] La firma copre solo `operationId`, non un payload di trasferimento — RISCHIO CONDIZIONALE

- **Riferimenti:**
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:65` — viene firmato il solo identificatore dell'operazione.
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:100-104` — HMAC riceve esclusivamente i byte di `operationId`.
  - `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:114-115` — il risultato firmato contiene anche importo, identificativi dei movimenti e altri dati.
- **Flusso/scenario concreto:** se il partner usa `partnerSignature` per autenticare un messaggio che include importo, conto origine/destino o identificativi dei movimenti, la firma non vincola quei campi. Una firma valida per un `operationId` potrebbe quindi essere riutilizzata o associata a dati diversi; inoltre non emerge alcun timestamp che consenta di distinguere una firma vecchia da una nuova.
- **Impatto:** possibile alterazione o replay del contenuto associato alla firma, con impatto su integrità delle comunicazioni di trasferimento. **Questo non è un difetto contrattuale confermato:** nel repository non è presente una specifica del partner, un client notifica né un consumer della firma. Se il contratto firma soltanto un identificatore opaco e il partner rivalida i dati presso una fonte autoritativa, il rischio può non concretizzarsi.
- **Correzione proposta:** definire e documentare il contratto di firma del provider; firmare la rappresentazione canonica esattamente prevista, includendo i dati necessari e un timestamp/versione; verificare la validità con test di contratto e proteggere il replay secondo le regole del partner. Non introdurre un formato proprietario senza conferma della specifica ufficiale.

## Verifiche di confine e copertura

- **Client e dipendenze:** nessun import o uso di `RestClient`, `RestTemplate`, `WebClient`, Java `HttpClient`, Apache HTTP client, OkHttp, Feign, Retrofit, gRPC o WebSocket rilevato in `src/main`.
- **Configurazione:** `src/main/resources/application.yml:1-37` non contiene base URL, token, header, timeout o policy di resilienza per client esterni. `pom.xml:27-100` non contiene le relative librerie.
- **URL e SSRF/TLS/redirect:** nessun URL o endpoint controllato dal richiedente e nessun client HTTP dal quale possano derivare redirect, SSRF o disattivazione TLS.
- **Timeout/retry/circuit breaker/bulkhead:** non applicabili in assenza di un chiamante remoto. Non sono finding confermati né viene ipotizzato un fallback remoto.
- **Gestione errori e contratti HTTP:** non esiste un contratto di risposta HTTP da mappare; `GlobalExceptionHandler` riguarda esclusivamente le API REST in ingresso e resta fuori dallo scope esterno.
- **Contesto:** `src/main/java/com/lipari/bank/common/CorrelationIdFilter.java:19-35` popola l'MDC per la richiesta inbound. Non è presente un interceptor/filter outbound che lo propaghi, ma tale assenza non è un finding finché non esiste una chiamata esterna. Per una futura integrazione, il correlation ID dovrà essere propagato con un interceptor dedicato e verificato nei test.
- **Limiti:** rate limit, quota, backpressure e limiti di payload/risposta non sono applicabili a un client assente; dovranno essere definiti con l'eventuale partner.
- **Test:** `src/test/java/com/lipari/bank/TransferIT.java:20-33` copre solo il trasferimento locale. Non sono presenti mock server o test WireMock/MockWebServer per successo, errore HTTP, timeout, payload malformato, retry o contratto. Tali test non sono un finding separato per l'attuale scope, perché non esiste un flusso API esterno da testare; sono prerequisiti per una futura integrazione.

## Comandi e risultati

1. `mvn -q -DskipTests package` — **riuscito**; il progetto e il JAR vengono compilati senza esecuzione di test.
2. `mvn test` — **BUILD SUCCESS**, ma Surefire non ha eseguito test: non viene riportato alcun test e `TransferIT` non è incluso nel naming/provisioning predefinito di Surefire.
3. `jar tf "target\liparibank-spring-target-1.0.0.jar" | Select-String -SimpleMatch "TransferExecutionService.class"` — **riuscito**; confermata la presenza della classe contenente la logica di firma nel JAR.
4. Scansioni statiche mirate su sorgenti, test, configurazioni e `pom.xml` per client HTTP/gRPC, URL esterni, dipendenze di resilienza e mock server — **nessun client esterno trovato**; unica evidenza nel perimetro esterno è la credenziale di firma sopra descritta.

Nessuna chiamata è stata effettuata verso servizi reali.
