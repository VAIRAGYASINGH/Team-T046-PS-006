package com.ps006.upiswitch;

import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.command.dispatcher.CommandDispatcher;
import com.ps006.upiswitch.command.loader.HolidayLoader;
import com.ps006.upiswitch.command.loader.MasterDataInitializer;
import com.ps006.upiswitch.command.loader.MerchantLoader;
import com.ps006.upiswitch.command.loader.UserLoader;
import com.ps006.upiswitch.command.parser.CommandParser;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.dispute.service.DisputeService;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.service.PaymentService;
import com.ps006.upiswitch.reporting.service.ReportGeneratorService;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.validation.service.ValidationPipelineService;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class ContextLoadTest {

    @Autowired private UserService userService;
    @Autowired private MerchantService merchantService;
    @Autowired private AccountService accountService;
    @Autowired private VpaService vpaService;
    @Autowired private CounterService counterService;
    @Autowired private LedgerService ledgerService;
    @Autowired private PaymentService paymentService;
    @Autowired private DisputeService disputeService;
    @Autowired private ValidationPipelineService validationPipelineService;
    @Autowired private CommandParser commandParser;
    @Autowired private CommandDispatcher commandDispatcher;
    @Autowired private ReportGeneratorService reportGeneratorService;
    @Autowired private UserLoader userLoader;
    @Autowired private MerchantLoader merchantLoader;
    @Autowired private HolidayLoader holidayLoader;
    @Autowired private MasterDataInitializer masterDataInitializer;

    @Test
    @DisplayName("Verify Spring application context loads with all modular monolith beans")
    void contextLoads() {
        assertNotNull(userService);
        assertNotNull(merchantService);
        assertNotNull(accountService);
        assertNotNull(vpaService);
        assertNotNull(counterService);
        assertNotNull(ledgerService);
        assertNotNull(paymentService);
        assertNotNull(disputeService);
        assertNotNull(validationPipelineService);
        assertNotNull(commandParser);
        assertNotNull(commandDispatcher);
        assertNotNull(reportGeneratorService);
        assertNotNull(userLoader);
        assertNotNull(merchantLoader);
        assertNotNull(holidayLoader);
        assertNotNull(masterDataInitializer);
    }
}

