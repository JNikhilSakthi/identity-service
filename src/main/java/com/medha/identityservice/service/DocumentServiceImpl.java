package com.medha.identityservice.service;

import com.medha.identityservice.dto.DocumentRequest;
import com.medha.identityservice.dto.DocumentResponse;
import com.medha.identityservice.entity.Document;
import com.medha.identityservice.exception.ResourceNotFoundException;
import com.medha.identityservice.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentServiceImpl(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public DocumentResponse create(DocumentRequest request, String ownerUsername) {
        Document document = Document.builder()
                .title(request.title())
                .content(request.content())
                .ownerUsername(ownerUsername)
                .build();
        return DocumentResponse.from(documentRepository.save(document));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> findAll() {
        return documentRepository.findAll().stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse findById(Long id) {
        return DocumentResponse.from(getOrThrow(id));
    }

    @Override
    public DocumentResponse update(Long id, DocumentRequest request) {
        Document document = getOrThrow(id);
        document.setTitle(request.title());
        document.setContent(request.content());
        return DocumentResponse.from(documentRepository.save(document));
    }

    @Override
    public void delete(Long id) {
        Document document = getOrThrow(id);
        documentRepository.delete(document);
    }

    private Document getOrThrow(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + id));
    }
}
