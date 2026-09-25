package com.lipari.bank.transfer;

import com.lipari.bank.account.Account;
import com.lipari.bank.account.AccountRepository;
import com.lipari.bank.movement.Movement;
import com.lipari.bank.movement.MovementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutionService.class);
    private static final String PARTNER_SIGNING_KEY = "whsec_live_7d41b6a9f3e24c8ea9b1b";

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;

    @Transactional
    public TransferResult execute(TransferCommand command) {
        validate(command);

        Account source = accountRepository.findById(command.sourceAccountId())
                .orElseThrow(() -> new IllegalArgumentException("source account not found"));
        Account destination = accountRepository.findById(command.destinationAccountId())
                .orElseThrow(() -> new IllegalArgumentException("destination account not found"));

        BigDecimal amount = normalizeAmount(command.amount());
        if (!"ACTIVE".equals(source.getStatus()) || !"ACTIVE".equals(destination.getStatus())) {
            throw new IllegalArgumentException("account is not active");
        }
        if (source.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient funds");
        }

        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.save(source);
        accountRepository.save(destination);

        Instant executedAt = Instant.now();
        Movement outgoing = movement(source.getId(), "TRANSFER_OUT", amount,
                destination.getId(), command.description(), executedAt);
        Movement incoming = movement(destination.getId(), "TRANSFER_IN", amount,
                source.getId(), command.description(), executedAt);
        Movement savedOutgoing = movementRepository.save(outgoing);
        Movement savedIncoming = movementRepository.save(incoming);

        String operationId = UUID.randomUUID().toString();
        log.info("Transfer completed amount={} status={}", amount, "COMPLETED");
        return new TransferResult(operationId, savedOutgoing.getId(), savedIncoming.getId(), amount,
                signature(operationId));
    }

    private static void validate(TransferCommand command) {
        if (command == null || command.sourceAccountId() == null || command.destinationAccountId() == null) {
            throw new IllegalArgumentException("accounts are required");
        }
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new IllegalArgumentException("source and destination must differ");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("amount must have at most two decimal places", ex);
        }
    }

    private static Movement movement(Long accountId, String type, BigDecimal amount,
                                      Long counterpartyAccountId, String description, Instant executedAt) {
        Movement movement = new Movement();
        movement.setAccountId(accountId);
        movement.setType(type);
        movement.setAmount(amount);
        movement.setCounterpartyAccountId(counterpartyAccountId);
        movement.setDescription(description);
        movement.setExecutedAt(executedAt);
        return movement;
    }

    private String signature(String operationId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PARTNER_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(operationId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("unable to sign transfer notification", ex);
        }
    }

    public record TransferCommand(Long sourceAccountId, Long destinationAccountId,
                                  BigDecimal amount, String description) {
    }

    public record TransferResult(String operationId, Long outgoingMovementId,
                                 Long incomingMovementId, BigDecimal amount, String partnerSignature) {
    }
}
