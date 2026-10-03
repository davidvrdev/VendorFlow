package com.vendorflow.document.api;

import com.vendorflow.document.application.DocumentTypeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-types")
public class DocumentTypeController {

    private final DocumentTypeService service;

    public DocumentTypeController(DocumentTypeService service) {
        this.service = service;
    }

    /** Default: active types (any role). {@code includeInactive=true}: all types with {@code active} (REQUIREMENTS_MANAGE). */
    @GetMapping
    public List<?> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return includeInactive ? service.listAll() : service.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentTypeAdminView create(@Valid @RequestBody DocumentTypeCreateRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public DocumentTypeAdminView update(@PathVariable UUID id, @Valid @RequestBody DocumentTypeUpdateRequest request) {
        return service.update(id, request);
    }
}
