package com.medha.identityservice.service;

import com.medha.identityservice.dto.DocumentRequest;
import com.medha.identityservice.dto.DocumentResponse;

import java.util.List;

public interface DocumentService {

    DocumentResponse create(DocumentRequest request, String ownerUsername);

    List<DocumentResponse> findAll();

    DocumentResponse findById(Long id);

    DocumentResponse update(Long id, DocumentRequest request);

    void delete(Long id);
}
