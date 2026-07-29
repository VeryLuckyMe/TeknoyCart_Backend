package com.teknoycart.controllers;

import com.teknoycart.dto.PublicStoreDTO;
import com.teknoycart.models.Store;
import com.teknoycart.repositories.StoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@CrossOrigin
@RestController
@RequestMapping("/stores")
public class StoreController {

    @Autowired
    private StoreRepository storeRepository;

    @GetMapping("/{storeId}")
    public ResponseEntity<PublicStoreDTO> getStoreById(@PathVariable UUID storeId) {
        return storeRepository.findById(storeId)
                .map(this::mapToPublicDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<PublicStoreDTO>> getAllStores() {
        List<PublicStoreDTO> publicStores = storeRepository.findAll().stream()
                .map(this::mapToPublicDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(publicStores);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchStoresByName(@RequestParam("name") String name) {
        return storeRepository.findByStoreName(name)
                .map(this::mapToPublicDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private PublicStoreDTO mapToPublicDTO(Store store) {
        PublicStoreDTO dto = new PublicStoreDTO();
        dto.setStoreId(store.getStoreId());
        dto.setStoreName(store.getStoreName());
        dto.setBannerUrl(store.getBannerUrl());
        dto.setLogoUrl(store.getLogoUrl());
        dto.setRating(store.getRating());

        // 🔒 EMAIL MASKING RULE:
        // Expose ONLY the public support email, NEVER the store.getOwner().getEmail() (personal account email)
        if (store.getPublicSupportEmail() != null && !store.getPublicSupportEmail().trim().isEmpty()) {
            dto.setPublicSupportEmail(store.getPublicSupportEmail());
        } else {
            // Safe fallback template based on the store name
            String safeEmail = "support." + store.getStoreName().toLowerCase().replaceAll("\\s+", "") + "@cit.edu";
            dto.setPublicSupportEmail(safeEmail);
        }

        return dto;
    }
}
