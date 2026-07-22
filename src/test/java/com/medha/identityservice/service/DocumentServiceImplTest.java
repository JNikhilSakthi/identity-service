package com.medha.identityservice.service;

import com.medha.identityservice.dto.DocumentRequest;
import com.medha.identityservice.dto.DocumentResponse;
import com.medha.identityservice.entity.Document;
import com.medha.identityservice.exception.ResourceNotFoundException;
import com.medha.identityservice.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for the service layer -- no Spring context, no security,
 * no database. Mockito stands in for {@link DocumentRepository}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private DocumentServiceImpl documentService;

    @Test
    void create_persistsDocumentWithOwnerFromToken() {
        DocumentRequest request = new DocumentRequest("Onboarding Guide", "Welcome to the company");
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(1L);
            doc.setCreatedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            return doc;
        });

        DocumentResponse response = documentService.create(request, "alice");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerUsername()).isEqualTo("alice");
        assertThat(captor.getValue().getTitle()).isEqualTo("Onboarding Guide");
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.ownerUsername()).isEqualTo("alice");
    }

    @Test
    void findAll_mapsEveryEntityToResponse() {
        Document doc1 = Document.builder().id(1L).title("A").content("a").ownerUsername("alice")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        Document doc2 = Document.builder().id(2L).title("B").content("b").ownerUsername("bob")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(documentRepository.findAll()).thenReturn(List.of(doc1, doc2));

        List<DocumentResponse> result = documentService.findAll();

        assertThat(result).hasSize(2)
                .extracting(DocumentResponse::title)
                .containsExactly("A", "B");
    }

    @Test
    void findById_throwsResourceNotFoundException_whenMissing() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void update_appliesNewTitleAndContent_whenDocumentExists() {
        Document existing = Document.builder().id(5L).title("Old").content("Old content")
                .ownerUsername("alice").createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(documentRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentResponse response = documentService.update(5L, new DocumentRequest("New", "New content"));

        assertThat(response.title()).isEqualTo("New");
        assertThat(response.content()).isEqualTo("New content");
    }

    @Test
    void update_throwsResourceNotFoundException_whenMissing() {
        when(documentRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.update(1L, new DocumentRequest("t", "c")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void delete_removesExistingDocument() {
        Document existing = Document.builder().id(7L).title("T").content("C").ownerUsername("alice")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(existing));

        documentService.delete(7L);

        verify(documentRepository).delete(existing);
    }

    @Test
    void delete_throwsResourceNotFoundException_whenMissing() {
        when(documentRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.delete(1L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(documentRepository, never()).delete(any(Document.class));
    }
}
