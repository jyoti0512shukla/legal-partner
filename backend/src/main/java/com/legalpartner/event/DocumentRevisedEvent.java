package com.legalpartner.event;

import java.util.UUID;

/** Published when a new version of a document is saved (e.g. an ONLYOFFICE edit). */
/**
 * A new version of a document was saved. {@code source} is the DocumentVersion source
 * (EDIT = in-app editor, UPLOAD, COUNTERPARTY …); learning only treats the firm's own
 * sources as lawyer edits (learning.yml edits.firm_edit_sources).
 */
public record DocumentRevisedEvent(UUID documentId, int versionNumber, String storedPath, String editedBy, String source) {}
