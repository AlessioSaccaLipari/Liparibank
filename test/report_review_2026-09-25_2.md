# Code review specialistica sugli importi — LipariBank

- **Data:** 2026-09-25
- **Ambito:** esclusivamente input numerici, validazione e rappresentazione degli importi, `BigDecimal`, scala e arrotondamento, limiti, valuta, vincoli del dominio applicabili e copertura dei test.
- **Modalità:** review read-only del codice; nessun file sorgente o di configurazione modificato.

## Riepilogo

La review ha rilevato **6 finding**: **1 CRITICAL, 2 HIGH, 3 MEDIUM**. I punti principali sono una verifica del saldo non protetta da concorrenza, invarianti monetaryi applicate solo al DTO, scala non normalizzata nel servizio realmente cablato, assenza di controllo sulla valuta e sui limiti di persistenza, e una suite di test che non viene eseguita.

## Findings

### [CRITICAL] 1. Verifica dei fondi e scrittura del saldo sono vulnerabili a TOCTOU

**Riferimenti:** `src/main/java/com/lipari/bank/movement/MovementService.java:35-47`; `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:36-52`; `src/main/java/com/lipari/bank/account/AccountRepository.java:5`.

**Stato:** difetto confermato; lo scenario richiede due richieste concorrenti.

**Input/scenario concreto:** il conto A ha saldo `100.00`, il conto B ha saldo `500.00`. Due richieste concorrenti da `60.00` leggono entrambe A prima che una transazione termini. Entrambe vedono `100.00 >= 60.00`, impostano A a `40.00` e B a `560.00`, quindi entrambe persistono i propri movimenti.

**Descrizione:** `MovementService` esegue `findById`, controlla il saldo e solo dopo modifica le entità. `AccountRepository` non espone alcun lock e `Account` non ha `@Version`; non esiste neppure un update SQL atomico condizionale. La transazione `@Transactional` non rende atomici il controllo e la scrittura rispetto a un'altra transazione. Lo stesso pattern è presente nel secondo servizio di trasferimento.

**Impatto:** entrambe le richieste possono ricevere esito positivo, ma il secondo salvataggio sovrascrive il primo: i saldi riflettono un solo trasferimento da `60.00`, mentre il registro movimenti contiene due coppie da `60.00` (`120.00` complessivi). Saldi e movimenti non sono riconciliabili e la decisione di sufficienza dei fondi è basata su uno stato non più valido.

**Correzione proposta:** usare un update atomico sul debitore, per esempio `balance = balance - :amount WHERE id = :id AND balance >= :amount`, verificando il numero di righe aggiornate; in alternativa acquisire lock pessimistici sui conti interessati in ordine deterministico. `@Version` è un'alternativa solo con retry e nuova rilettura/validazione. Aggiungere un test concorrente che verifichi una sola autorizzazione e la riconciliazione dei movimenti.

### [HIGH] 2. Le invarianti sull'importo non sono validate nel servizio attivamente utilizzato

**Riferimenti:** `src/main/java/com/lipari/bank/movement/MovementService.java:30-45`; `src/main/java/com/lipari/bank/movement/MovementService.java:49-65`; `src/main/java/com/lipari/bank/movement/dto/TransferRequest.java:16-18`; `src/main/java/com/lipari/bank/movement/MovementController.java:20-21`; `src/test/java/com/lipari/bank/TransferIT.java:28`; `src/main/resources/db/changelog/001-base-schema.yaml:40`.

**Stato:** difetto confermato al confine del servizio; il controller HTTP esterno impedisce attualmente questi casi, ma un caller interno li ammette.

**Input/scenario concreto:** una chiamata interna a `MovementService.transfer` con importo `-10.00` superata la sola verifica `balance >= amount`; il conto mittente viene accreditato di `10.00` e quello destinatario addebitato di `10.00`. Con `0.00` vengono creati movimenti a zero; con `null`, `compareTo(null)` provoca `NullPointerException`.

**Descrizione:** `@NotNull` e `@DecimalMin` vivono soltanto sul DTO e sono applicati unicamente da `@Valid` nel controller. `MovementService` è un metodo pubblico e non ripete i controlli prima del calcolo. Il test esistente, inoltre, chiama il servizio direttamente e bypassa la validazione del controller. Lo schema vieta gli importi nulli tramite il vincolo `NOT NULL`, ma non vieta importi zero o negativi.

**Impatto:** un uso interno può invertire il senso del trasferimento, registrare importi non monetari e produrre un errore runtime non controllato. La validazione DTO non è una difesa sufficiente per un servizio finanziario.

**Correzione proposta:** validare centralmente `null`, segno e scala all'inizio di ogni entry point del dominio, prima di leggere o modificare i saldi, e utilizzare la stessa istanza normalizzata per controllo, aritmetica, movimenti e risposta. Aggiungere inoltre vincoli DB positivi per `movement.amount` (e per `account.balance` se il prodotto non ammette saldo negativo).

### [HIGH] 3. Gli importi con più di due cifre decimali superano il DTO e vengono convertiti implicitamente dal database

**Riferimenti:** `src/main/java/com/lipari/bank/movement/dto/TransferRequest.java:16-18`; `src/main/java/com/lipari/bank/movement/MovementService.java:40-45`; `src/main/java/com/lipari/bank/movement/MovementService.java:52,61,67`; `src/main/java/com/lipari/bank/account/Account.java:21-22`; `src/main/java/com/lipari/bank/movement/Movement.java:21-22`; `src/main/resources/db/changelog/001-base-schema.yaml:25,40`; `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:80-85`.

**Stato:** difetto confermato sul percorso HTTP; l'effetto numerico finale durante la persistenza non è stato riprodotto perché il daemon Docker/MySQL non era disponibile.

**Input/scenario concreto:** `{"fromAccountId":1,"toAccountId":2,"amount":1.005}`. Una sonda con il deserializzatore Spring/Jackson e Hibernate Validator ha confermato che `1.005` e la stringa `"1.005"` producono zero violazioni. Con saldi `1000.00` e `500.00`, Java calcola `998.995` e `501.005`, entrambi con scala 3.

**Descrizione:** il DTO non ha `@Digits(fraction = 2)` e il servizio cablato non esegue alcun `setScale`. Le entità e Liquibase usano correttamente `DECIMAL(19,2)`, ma il valore a scala 3 arriva al mapping. La risposta viene costruita dagli oggetti in memoria prima che il database applichi la conversione a scala 2. `TransferExecutionService` contiene già `setScale(2, RoundingMode.UNNECESSARY)`, ma non è il servizio invocato da `MovementController`.

**Impatto:** importo richiesto, quantità persistite e saldi restituiti possono non coincidere. La conversione separata del saldo debitore e di quello creditore può alterare la conservazione del totale di un centesimo; l'importo effettivamente registrato può inoltre differire da quello richiesto.

**Correzione proposta:** definire un'unica politica monetaria a due cifre. Al boundary applicare `@Digits(integer = 17, fraction = 2)` se il contratto vieta anche cifre decimali superflue; nel dominio normalizzare sempre con `setScale(2, RoundingMode.UNNECESSARY)`, rifiutando qualunque arrotondamento. Usare l'importo normalizzato in ogni calcolo, movimento e risposta.

### [MEDIUM] 4. I trasferimenti non verificano la compatibilità della valuta

**Riferimenti:** `src/main/java/com/lipari/bank/account/Account.java:24-25`; `src/main/resources/db/changelog/001-base-schema.yaml:26`; `src/main/java/com/lipari/bank/movement/MovementService.java:35-45`; `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:36-50`; `src/main/java/com/lipari/bank/movement/dto/TransferRequest.java:9-20`; `src/main/java/com/lipari/bank/movement/dto/TransferResponse.java:9-13`; `src/main/java/com/lipari/bank/movement/Movement.java:15-30`.

**Stato:** rischio condizionale; diventa immediatamente applicabile se esistono conti non EUR o se il prodotto abilita altre valute.

**Input/scenario concreto:** A è EUR, B è USD e il client invia `amount = 10.00` senza indicare la valuta. Il servizio carica entrambi i conti, ma non confronta `currency`: accredita 10 unità su USD dopo averne addebitate 10 su EUR.

**Descrizione:** il modello dati contiene una valuta esplicita sul conto, ma la richiesta, il movimento e la risposta di trasferimento non la riportano e nessun servizio verifica l'uguaglianza delle valute. Non esiste una conversione autorizzata né un controllo ISO esplicito.

**Impatto:** valori con unità diverse vengono sommati come se fossero intercambiabili; i movimenti non sono autonomamente interpretabili e la risposta espone due saldi senza sapere quali unità rappresentano.

**Correzione proposta:** se il trasferimento cross-currency non è supportato, rifiutare esplicitamente account con valute diverse e validare il codice valuta. Se sarà supportato, includere la valuta nella richiesta, persisterla sul movimento, verificare il tasso e la data di conversione autorizzati e applicare regole di arrotondamento esplicite a debito e credito.

### [MEDIUM] 5. Non esiste un limite numerico all'importo né un controllo di overflow del nuovo saldo

**Riferimenti:** `src/main/java/com/lipari/bank/movement/dto/TransferRequest.java:16-18`; `src/main/java/com/lipari/bank/movement/MovementService.java:44-45`; `src/main/java/com/lipari/bank/transfer/TransferExecutionService.java:49-50`; `src/main/resources/db/changelog/001-base-schema.yaml:25,40`.

**Stato:** assenza del limite confermata; l'overflow concreto è condizionato a saldi prossimi al massimo supportato.

**Input/scenario concreto:** la sonda ha confermato che `1E+20` viene deserializzato come BigDecimal esatto e supera `@DecimalMin`. Inoltre, con A e B al massimo `DECIMAL(19,2)`, `99999999999999999.99`, un importo valido di `0.01` supera il range della somma B + 0.01, che richiede 18 cifre intere.

**Descrizione:** `DECIMAL(19,2)` ammette al massimo 17 cifre intere, ma il DTO non ha `@Digits(integer = 17, ...)` e il servizio non confronta l'importo con tale massimo né controlla il saldo risultante prima di salvarlo. `BigDecimal` non va in overflow da solo; il limite si manifesta al mapping DB. Non è stato trovato un limite di business documentato oltre alla capacità del schema.

**Impatto:** importi fuori range non vengono rifiutati al boundary; un saldo prossimo al massimo può produrre un errore al commit e una risposta 500, oppure una perdita di integrità se l'ambiente DB applica conversioni/clipping. Il risultato dipende quindi dal runtime, non da una regola finanziaria esplicita.

**Correzione proposta:** applicare `@Digits(integer = 17, fraction = 2)` e un eventuale massimo di business con `@DecimalMax`; ripetere i controlli nel dominio. Prima della scadenza, verificare anche `destinationBalance <= MAX_BALANCE - amount` con `BigDecimal.compareTo`, così da rifiutare l'overflow senza conversioni implicite.

### [MEDIUM] 6. L'unico test esistente non viene eseguito e non copre i casi limite degli importi

**Riferimenti:** `pom.xml:102-108`; `src/test/java/com/lipari/bank/TransferIT.java:16,20-33`.

**Stato:** difetto confermato.

**Input/scenario concreto:** esecuzione di `mvn clean test` dal progetto. La compilazione delle 21 classi di produzione e dell'unico test riesce, ma Surefire non esegue `TransferIT`; non vengono creati report Surefire o Failsafe e la build conclude con zero test eseguiti. Il POM non configura il plugin Failsafe per i suffissi `*IT`.

**Descrizione:** l'unico caso è un happy path con `new BigDecimal("100.00")`, invocato direttamente sul servizio. Non copre parsing e validazione controller, `null`, stringa vuota, zero, negativo, minimo `0.01`, saldo insufficiente di un centesimo, più di due decimali, notazione esponenziale, valori massimi/overflow, scala, arrotondamento, valuta o concorrenza.

**Impatto:** le principali invarianti monetaryhe e tutte le regressioni sui casi limite possono restare verdi nella build, anche quando il test esistente non viene eseguito.

**Correzione proposta:** rinominare il test secondo i pattern Surefire oppure configurare Maven Failsafe per gli integration test. Aggiungere test unitari del validatore di dominio, test del controller per il parsing/Bean Validation e test MySQL/Testcontainers per scala, precisione, range, persistenza e due trasferimenti concorrenti.

## Verifiche eseguite

- Esaminati i sorgenti del percorso di trasferimento, DTO, entità, repository, servizi, mapping JPA, schema Liquibase, configurazione applicativa, POM e test.
- Eseguita ricerca mirata su `BigDecimal`, `double`, `float`, conversioni primitive, `setScale`, `RoundingMode`, `compareTo`, vincoli e mapping `DECIMAL`: nessun uso di `double`/`float` e nessun `new BigDecimal(double)`; il confronto del saldo usa `compareTo`; i confronti del test usano `isEqualByComparingTo`.
- Verificata la coerenza tra `@Column(precision = 19, scale = 2)` e Liquibase `DECIMAL(19,2)` per saldi e importi.
- Verificato il parsing con una sonda temporanea esterna al progetto: `null`, stringa vuota e solo spazi diventano `null` e sono respinti da `@NotNull`; virgole, separatori di migliaia, `NaN` e booleani sono respinti da Jackson; `1.001`, `1e2` e `1E+20` passano i controlli sull'importo.
- Verificato con BigDecimal che `1000.00 - 1.005 = 998.995` e `500.00 + 1.005 = 501.005`, confermando la scala 3 nel servizio attivo.
- Eseguito `mvn clean test`: build riuscita, ma nessun test eseguito.
- Verificata la disponibilità del daemon Docker: non disponibile; non è stato quindi possibile eseguire un test live MySQL del rounding e dell'overflow. Questo limite è dichiarato anche nei finding interessati.
- Nessun codice sorgente, test o configurazione esistente è stato modificato.
