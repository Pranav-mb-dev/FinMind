package com.smartspend.transaction;

import com.smartspend.common.GlobalExceptionHandler;
import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.document.DocumentRepository;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PendingTransactionControllerTest {

    private final PendingTransactionService service = mock(PendingTransactionService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PendingTransactionController controller =
            new PendingTransactionController(service, documents, users);
    private final UUID userId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("a@b.c", null, List.of()));
        User u = new User();
        u.setId(userId);
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(u));
        when(documents.findByIdAndUserId(docId, userId)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listForeignDocumentIs404() {
        assertThrows(ResourceNotFoundException.class, () -> controller.list(docId));
        verifyNoInteractions(service);
    }

    @Test
    void confirmForeignDocumentIs404() {
        ConfirmRequest req = new ConfirmRequest(List.of(UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> controller.confirm(docId, req));
        verifyNoInteractions(service);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String updateBody(String amount) {
        return "{\"amount\": " + amount + ", \"type\": \"EXPENSE\", \"transactionDate\": \"2026-01-02\"}";
    }

    @Test
    void oversizedAmountIs400WithErrorBody() throws Exception {
        mockMvc().perform(put("/pending-transactions/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1e12")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(service);
    }

    @Test
    void validationFailureUsesErrorBodyStyle() throws Exception {
        mockMvc().perform(put("/pending-transactions/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("amount")));
        verifyNoInteractions(service);
    }

    @Test
    void serviceIllegalArgumentIs400WithErrorBody() throws Exception {
        when(service.update(eq(userId), any(), any())).thenThrow(new IllegalArgumentException("Amount too large"));
        mockMvc().perform(put("/pending-transactions/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Amount too large"));
    }

    @Test
    void illegalArgumentMapsTo400() {
        ResponseEntity<Map<String, String>> r =
                new GlobalExceptionHandler().handleIllegalArgumentException(new IllegalArgumentException("bad"));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("bad", r.getBody().get("error"));
    }
}
