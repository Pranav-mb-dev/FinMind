package com.smartspend.document;

import com.smartspend.transaction.PendingTransactionRepository;
import com.smartspend.transaction.PendingTransactionService;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DocumentStatusExtractionTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final PendingTransactionService service = mock(PendingTransactionService.class);
    private final PendingTransactionRepository pendingRepo = mock(PendingTransactionRepository.class);
    private DocumentController controller;
    private final UUID userId = UUID.randomUUID();
    private Document doc;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentRepository, userRepository, restTemplate, service, pendingRepo);
        ReflectionTestUtils.setField(controller, "aiServiceUrl", "http://ai");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("a@b.c", null, List.of()));
        User user = new User();
        user.setId(userId);
        when(userRepository.findByEmail("a@b.c")).thenReturn(Optional.of(user));
        doc = Document.builder().id(UUID.randomUUID()).userId(userId).filename("f.pdf")
                .status("PROCESSING").celeryTaskId("t1").build();
        when(documentRepository.findByIdAndUserId(doc.getId(), userId)).thenReturn(Optional.of(doc));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void respond(Map<String, Object> body) {
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);
    }

    private Map<String, Object> ready() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ready");
        m.put("chunks_indexed", 5);
        return m;
    }

    private Map<String, Object> row() {
        return Map.of("date", "2026-01-02", "amount", 10, "type", "EXPENSE");
    }

    @Test
    void readyWithRowsSavesOnce() {
        Map<String, Object> m = ready();
        m.put("transactions", List.of(row()));
        m.put("extraction_error", null);
        respond(m);

        DocumentResponse r = controller.getDocumentStatus(doc.getId()).getBody();

        verify(service, times(1)).saveExtracted(eq(userId), eq(doc.getId()), anyList());
        assertEquals("READY", r.getStatus());
        assertEquals(5, r.getChunksIndexed());
    }

    @Test
    void extractionErrorIsWarning() {
        Map<String, Object> m = ready();
        m.put("transactions", List.of(row()));
        m.put("extraction_error", "boom");
        respond(m);

        DocumentResponse r = controller.getDocumentStatus(doc.getId()).getBody();

        assertEquals("READY", r.getStatus());
        assertEquals("boom", r.getErrorMessage());
        verify(service, never()).saveExtracted(any(), any(), any());
    }

    @Test
    void legacyResponseDoesNotThrow() {
        respond(ready());
        DocumentResponse r = assertDoesNotThrow(() -> controller.getDocumentStatus(doc.getId()).getBody());
        assertEquals("READY", r.getStatus());
        verify(service, never()).saveExtracted(any(), any(), any());
    }

    @Test
    void dataIntegrityViolationSwallowed() {
        Map<String, Object> m = ready();
        m.put("transactions", List.of(row()));
        respond(m);
        doThrow(new DataIntegrityViolationException("dup")).when(service).saveExtracted(any(), any(), any());

        DocumentResponse r = controller.getDocumentStatus(doc.getId()).getBody();

        assertEquals("READY", r.getStatus());
        assertNull(r.getErrorMessage());
    }

    @Test
    void otherExceptionStoresGenericWarningAndLogsCause(CapturedOutput output) {
        Map<String, Object> m = ready();
        m.put("transactions", List.of(row()));
        respond(m);
        doThrow(new IllegalStateException("db down: relation pending_transactions violates constraint"))
                .when(service).saveExtracted(any(), any(), any());

        DocumentResponse r = controller.getDocumentStatus(doc.getId()).getBody();

        assertEquals("READY", r.getStatus());
        assertEquals("Transactions were extracted but could not be saved. Please re-upload the statement.",
                r.getErrorMessage());
        assertFalse(r.getErrorMessage().contains("db down"));
        // The internal cause is logged server-side rather than exposed to the user.
        assertTrue(output.getAll().contains("db down: relation pending_transactions violates constraint"));
    }
}
