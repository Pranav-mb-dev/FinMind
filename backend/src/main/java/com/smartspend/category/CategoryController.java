package com.smartspend.category;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getCategories() {
        UUID userId = getCurrentUserId();
        List<CategoryResponse> categories = categoryRepository.findByUserIdOrIsSystemTrue(userId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        UUID userId = getCurrentUserId();
        Category category = Category.builder()
                .userId(userId)
                .name(request.getName())
                .icon(request.getIcon())
                .isSystem(false)
                .build();
        Category saved = categoryRepository.save(category);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (!userId.equals(category.getUserId())) {
            throw new ResourceNotFoundException("Category not found");
        }
        if (category.isSystem()) {
            throw new IllegalArgumentException("Cannot delete system categories");
        }
        categoryRepository.delete(category);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return user.getId();
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .icon(category.getIcon())
                .isSystem(category.isSystem())
                .createdAt(category.getCreatedAt())
                .build();
    }
}
