package com.lipari.bank.account;

import java.math.BigDecimal;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountBalanceService {

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public AccountBalanceView getBalanceForCustomer(Long accountId, Long customerId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));

        if (customerId == null || !Objects.equals(customerId, account.getCustomerId())) {
            throw new IllegalArgumentException("account is not owned by customer");
        }

        return new AccountBalanceView(
                account.getId(),
                account.getIban(),
                account.getBalance(),
                account.getCurrency());
    }

    public record AccountBalanceView(Long accountId, String iban, BigDecimal balance, String currency) {
    }
}
