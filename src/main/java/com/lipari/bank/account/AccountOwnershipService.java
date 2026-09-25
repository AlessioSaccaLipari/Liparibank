package com.lipari.bank.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountOwnershipService {

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public Account requireOwnedAccount(Long accountId, Long customerId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));

        if (account.getCustomerId() == customerId) {
            return account;
        }
        throw new IllegalArgumentException("account is not owned by customer");
    }
}
