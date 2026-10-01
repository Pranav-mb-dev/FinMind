package com.smartspend.document;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getDocuments() {
        UUID userId = getCurrentUserId();
        List<DocumentResponse> docs = documentRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(docs);
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentResponse> uploadDocument(@RequestParam("file") MultipartFile file) throws Exception {
        UUID userId = getCurrentUserId();

        String filename = file.getOriginalFilename();
        String extension = "";
        if (filename != null && filename.contains(".")) {
            extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        }

        Document document = Document.builder()
                .userId(userId)
                .filename(filename != null ? filename : "unknown")
                .fileType(extension)
                .status("PROCESSING")
                .build();
        Document saved = documentRepository.save(document);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("user_id", userId.toString());
        body.add("document_id", saved.getId().toString());

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/api/v1/ingest",
                    requestEntity,
                    Map.class);

            if (response != null && response.containsKey("task_id")) {
                saved.setCeleryTaskId((String) response.get("task_id"));
                documentRepository.save(saved);
            }
        } catch (HttpClientErrorException e) {
            saved.setStatus("FAILED");
            documentRepository.save(saved);
            return ResponseEntity.status(e.getStatusCode()).body(toResponse(saved));
        } catch (Exception e) {
            saved.setStatus("FAILED");
            documentRepository.save(saved);
            throw e;
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(saved));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentResponse> getDocumentStatus(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        Document document = documentRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if ("PROCESSING".equals(document.getStatus()) && document.getCeleryTaskId() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> statusResponse = restTemplate.getForObject(
                        aiServiceUrl + "/api/v1/ingest/status/" + document.getCeleryTaskId(),
                        Map.class);

                if (statusResponse != null) {
                    String taskStatus = (String) statusResponse.get("status");
                    if ("ready".equals(taskStatus)) {
                        document.setStatus("READY");
                        Object chunks = statusResponse.get("chunks_indexed");
                        if (chunks instanceof Number) {
                            document.setChunksIndexed(((Number) chunks).intValue());
                        }
                        documentRepository.save(document);
                    } else if ("failed".equals(taskStatus)) {
                        document.setStatus("FAILED");
                        Object error = statusResponse.get("error");
                        if (error instanceof String) {
                            document.setErrorMessage((String) error);
                        }
                        documentRepository.save(document);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok(toResponse(document));
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return user.getId();
    }

    private DocumentResponse toResponse(Document d) {
        return DocumentResponse.builder()
                .id(d.getId())
                .filename(d.getFilename())
                .fileType(d.getFileType())
                .status(d.getStatus())
                .chunksIndexed(d.getChunksIndexed())
                .errorMessage(d.getErrorMessage())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
