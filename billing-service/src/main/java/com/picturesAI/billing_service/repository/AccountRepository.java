package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}