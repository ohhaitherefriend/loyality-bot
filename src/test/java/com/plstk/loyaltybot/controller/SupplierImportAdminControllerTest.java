package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.entity.ShopMember;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.AuthorizationService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Mockito's inline mock maker cannot subclass concrete classes on this JDK/Byte Buddy
 * combination, so only repository interfaces are mocked here. {@link AuthorizationService} is a
 * real instance backed by those mocks (so the actual role-resolution logic runs), and
 * {@link ManualImportUploadService} collaborators the controller depends on are stubbed with a
 * plain subclass that records whether {@code upload} was invoked instead of a Mockito mock.
 *
 * <p>Manual upload requires at least {@link MemberRole#ADMIN} (Stage 7): a non-member is
 * forbidden, a {@code STAFF} member is forbidden, and an {@code ADMIN} (or {@code OWNER}) member
 * is let through to the upload service.
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

    private SupplierImportAdminController controllerWith(RecordingManualImportUploadService uploadService) {
        AuthorizationService authorizationService = new AuthorizationService(shopRepository, shopMemberRepository);
        return new SupplierImportAdminController(
                authorizationService, null, null, null, uploadService, null);
    }

    private MockMultipartFile priceFile() {
        return new MockMultipartFile(
                "file",
                "price.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B, 0x03, 0x04});
    }

    @Test
    void manualUpload_forNonMember_isForbiddenBeforeReadingFile() throws IOException {
        RecordingManualImportUploadService manualImportUploadService = new RecordingManualImportUploadService();
        SupplierImportAdminController controller = controllerWith(manualImportUploadService);

        AdminUser user = AdminUser.builder().id(7L).email("operator@example.com").passwordHash("hash").build();
        when(shopRepository.findByShopId("other-shop")).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.findByUserIdAndShopId(7L, "other-shop")).thenReturn(Optional.empty());

        var response = controller.uploadManually("other-shop", 3L, priceFile(), "request-id", user);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertFalse(manualImportUploadService.called, "upload() must not run for a forbidden tenant");
    }

    @Test
    void manualUpload_forStaffMember_isForbidden() throws IOException {
        RecordingManualImportUploadService manualImportUploadService = new RecordingManualImportUploadService();
        SupplierImportAdminController controller = controllerWith(manualImportUploadService);

        AdminUser user = AdminUser.builder().id(9L).email("staff@example.com").passwordHash("hash").build();
        lenient().when(shopRepository.findByShopId("shop-1")).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.findByUserIdAndShopId(9L, "shop-1"))
                .thenReturn(Optional.of(ShopMember.builder().userId(9L).shopId("shop-1").role(MemberRole.STAFF).build()));

        var response = controller.uploadManually("shop-1", 3L, priceFile(), "request-id", user);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertFalse(manualImportUploadService.called, "upload() must not run for a STAFF-role caller");
    }

    @Test
    void manualUpload_forAdminMember_reachesUploadService() throws IOException {
        RecordingManualImportUploadService manualImportUploadService = new RecordingManualImportUploadService();
        SupplierImportAdminController controller = controllerWith(manualImportUploadService);

        AdminUser user = AdminUser.builder().id(11L).email("admin@example.com").passwordHash("hash").build();
        lenient().when(shopRepository.findByShopId("shop-1")).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.findByUserIdAndShopId(11L, "shop-1"))
                .thenReturn(Optional.of(ShopMember.builder().userId(11L).shopId("shop-1").role(MemberRole.ADMIN).build()));

        assertThrows(UnsupportedOperationException.class,
                () -> controller.uploadManually("shop-1", 3L, priceFile(), "request-id", user));

        assertTrue(manualImportUploadService.called, "upload() must run once the caller clears the ADMIN role check");
    }
}
