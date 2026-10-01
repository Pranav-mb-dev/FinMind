package com.smartspend.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {
    private UUID id;
    private String filename;
    private String fileType;
    private String status;
    private Integer chunksIndexed;
    private String errorMessage;
    private Instant createdAt;
}
