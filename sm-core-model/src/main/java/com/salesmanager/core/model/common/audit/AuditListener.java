package com.salesmanager.core.model.common.audit;

import java.util.Date;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

public class AuditListener {

  @PrePersist
  public void onSave(Object o) {
    if (o instanceof Auditable) {
      Auditable audit = (Auditable) o;
      AuditSection auditSection = audit.getAuditSection();

      auditSection.setDateModified(new Date());
      if (auditSection.getDateCreated() == null) {
        auditSection.setDateCreated(new Date());
      }
      stampActor(auditSection);
      audit.setAuditSection(auditSection);
    }
  }

  @PreUpdate
  public void onUpdate(Object o) {
    if (o instanceof Auditable) {
      Auditable audit = (Auditable) o;
      AuditSection auditSection = audit.getAuditSection();

      auditSection.setDateModified(new Date());
      if (auditSection.getDateCreated() == null) {
        auditSection.setDateCreated(new Date());
      }
      stampActor(auditSection);
      audit.setAuditSection(auditSection);
    }
  }

  /** The authenticated user, when there is one, is who changed the row. */
  static void stampActor(AuditSection auditSection) {
    String actor = AuditActor.current();
    if (actor != null && !actor.isEmpty()) {
      auditSection.setModifiedBy(actor);
    }
  }
}
