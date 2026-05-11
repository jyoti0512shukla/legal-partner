package com.legalpartner.repository;

import com.legalpartner.model.entity.DocumentNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface DocumentNoteRepository extends JpaRepository<DocumentNote, UUID> {

    @Query("SELECT n FROM DocumentNote n WHERE n.document.id = :documentId ORDER BY n.createdAt DESC")
    List<DocumentNote> findByDocumentIdOrderByCreatedAtDesc(@Param("documentId") UUID documentId);

    @Transactional
    void deleteByDocumentId(UUID documentId);
}
