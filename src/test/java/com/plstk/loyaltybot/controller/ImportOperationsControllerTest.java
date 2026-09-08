package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.entity.ShopMember;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.AuthorizationService;
import com.plstk.loyaltybot.service.importing.ImportBatchApprovalService;
import com.plstk.loyaltybot.service.importing.ImportDashboardResponse;
import com.plstk.loyaltybot.service.importing.ImportDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Stage 7: {@link ImportOperationsController} read endpoints accept any shop role (STAFF and up),
 * while mutating endpoints ({@code approve}, {@code resume}, row review, manual-hidden, rule-version
 * approve) require at least {@link MemberRole#ADMIN}. As in {@link SupplierImportAdminControllerTest},
 * only repository interfaces are mocked; concrete collaborator services are stubbed via a
 * recording subclass since Mockito's inline mock maker cannot subclass them on this JDK.
 */
@ExtendWith(MockitoExtension.class)
class ImportOperationsControllerTest {

    @Mock
    private ShopRepository shopRepository;
    @Mock
    private ShopMemberRepository shopMemberRepository;

    private static final String SHOP_ID = "shop-1";
    private static final AdminUser STAFF_USER = AdminUser.builder().id(21L).email("staff@example.com").build();
    private static final AdminUser ADMIN_USER = AdminUser.builder().id(22L).email("admin@example.com").build();

    private static final class RecordingDashboardService extends ImportDashboardService {
        private boolean called;

        RecordingDashboardService() {
            super(null, null, null, null, null);
        }

        @Override
        public ImportDashboardResponse buildDashboard(String shopId, int windowHours) {
            called = true;
            return new ImportDashboardResponse(
                    new ImportDashboardResponse.AutomationRate(0, 0, null),
                    new ImportDashboardResponse.BatchStatusCounts(0, 0, 0, 0, 0, 0),
                    0L,
                    List.of(),
                    List.of(),
                    new ImportDashboardResponse.RecentActivity(windowHours, 0, 0),
                    new ImportDashboardResponse.ProductChangeSummary(windowHours, 0, 0, 0, 0, 0));
        }
    }

    private static final class RecordingApprovalService extends ImportBatchApprovalService {
        private boolean called;

        RecordingApprovalService() {
            super(null, null, null);
        }

        @Override
        public Optional<ImportBatch> approve(String shopId, Long batchId, AdminUser approver) {
            called = true;
            return Optional.of(ImportBatch.builder().id(batchId).build());
        }
    }

    private void stubStaffMember() {
        lenient().when(shopRepository.findByShopId(SHOP_ID)).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.findByUserIdAndShopId(STAFF_USER.getId(), SHOP_ID))
                .thenReturn(Optional.of(ShopMember.builder().userId(STAFF_USER.getId()).shopId(SHOP_ID).role(MemberRole.STAFF).build()));
    }

    private void stubAdminMember() {
        lenient().when(shopRepository.findByShopId(SHOP_ID)).thenReturn(Optional.<Shop>empty());
        when(shopMemberRepository.findByUserIdAndShopId(ADMIN_USER.getId(), SHOP_ID))
                .thenReturn(Optional.of(ShopMember.builder().userId(ADMIN_USER.getId()).shopId(SHOP_ID).role(MemberRole.ADMIN).build()));
    }

    private ImportOperationsController controllerWith(
            RecordingDashboardService dashboardService, RecordingApprovalService approvalService) {
        AuthorizationService authorizationService = new AuthorizationService(shopRepository, shopMemberRepository);
        return new ImportOperationsController(
                authorizationService, dashboardService, null, null, null, null, approvalService, null, null);
    }

    @Test
    void getDashboard_forStaffMember_isAllowed() {
        stubStaffMember();
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        ImportOperationsController controller = controllerWith(dashboardService, null);

        var response = controller.getDashboard(SHOP_ID, 24, STAFF_USER);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(dashboardService.called, "STAFF must be able to read the dashboard");
    }

    @Test
    void approveBatch_forStaffMember_isForbidden() {
        stubStaffMember();
        RecordingApprovalService approvalService = new RecordingApprovalService();
        ImportOperationsController controller = controllerWith(null, approvalService);

        var response = controller.approveBatch(SHOP_ID, 5L, STAFF_USER);

        assertEquals(403, response.getStatusCode().value());
        assertFalse(approvalService.called, "STAFF must not be able to approve a batch");
    }

    @Test
    void approveBatch_forAdminMember_isAllowed() {
        stubAdminMember();
        RecordingApprovalService approvalService = new RecordingApprovalService();
        ImportOperationsController controller = controllerWith(null, approvalService);

        var response = controller.approveBatch(SHOP_ID, 5L, ADMIN_USER);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(approvalService.called, "ADMIN must be able to approve a batch");
    }
}
