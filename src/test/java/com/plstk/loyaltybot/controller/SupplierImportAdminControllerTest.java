package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.ShopAccessService;
import com.plstk.loyaltybot.service.importing.IngestionResult;
import com.plstk.loyaltybot.service.importing.ManualImportUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Mockito's inline mock maker cannot subclass concrete classes on this JDK/Byte Buddy
 * combination, so only repository interfaces are mocked here. {@link ShopAccessService} is a
 * real instance backed by those mocks (so the actual access-check logic runs), and
 * {@link ManualImportUploadService} collaborators the controller depends on are stubbed with a
 * plain subclass that records whether {@code upload} was invoked instead of a Mockito mock.
 */
@ExtendWith(MockitoExtension.class)
class SupplierImportAdminControllerTest {

    @Mock
    private ShopRepository shopRepository;
    @Mock
    private ShopMemberRepository shopMemberRepository;

    private static final class RecordingManualImportUploadService extends ManualImportUploadService {
        private boolean called;

        RecordingManualImportUploadService() {
            super(null, null, null);
        }

        @Override
        public IngestionResult upload(
                String shopId, Long supplierSourceId, MultipartFile file, String requestId, Long uploadedByUserId) {
            called = true;
            throw new UnsupportedOperationException("should not be reached for a forbidden request");
        }
    }

    @Test
    void manualUpload_forWrongTenant_isForbiddenBeforeReadingFile() throws IOException {
        ShopAccessService shopAccessService = new ShopAccessService(shopRepository, shopMemberRepository);
        RecordingManualImportUploadService manualImportUploadService = new RecordingManualImportUploadService();
        SupplierImportAdminController controller = new SupplierImportAdminController(
                shopAccessService, null, null, null, manualImportUploadService);

        AdminUser user = AdminUser.builder().id(7L).email("operator@example.com").passwordHash("hash").build();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "price.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B, 0x03, 0x04});
        when(shopRepository.findByShopId("other-shop")).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.existsByUserIdAndShopId(7L, "other-shop")).thenReturn(false);

        var response = controller.uploadManually("other-shop", 3L, file, "request-id", user);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertFalse(manualImportUploadService.called, "upload() must not run for a forbidden tenant");
    }
}
