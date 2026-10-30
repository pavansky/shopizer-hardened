package com.salesmanager.core.business.repositories.system;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.salesmanager.core.model.system.AdminAuditEvent;

/** Append-only: insert and read, no update or delete. */
public interface AdminAuditEventRepository extends Repository<AdminAuditEvent, Long> {

	AdminAuditEvent save(AdminAuditEvent event);

	List<AdminAuditEvent> findTop50ByUsernameOrderByIdDesc(String username);
}
