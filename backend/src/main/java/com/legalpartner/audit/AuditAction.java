package com.legalpartner.audit;

import com.legalpartner.model.enums.AuditActionType;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditAction {
    AuditActionType value();
}
