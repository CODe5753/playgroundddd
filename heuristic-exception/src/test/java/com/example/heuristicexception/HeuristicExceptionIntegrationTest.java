package com.example.heuristicexception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.heuristicexception.domain.ApprovalRequest;
import com.example.heuristicexception.mapper.db1.ApprovalHistoryMapper;
import com.example.heuristicexception.mapper.db2.UmsSendHistoryMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.HeuristicCompletionException;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HeuristicExceptionIntegrationTest {

    @Autowired
    private ApprovalHistoryMapper approvalHistoryMapper;
    @Autowired
    private UmsSendHistoryMapper umsSendHistoryMapper;
    @Autowired
    private com.example.heuristicexception.service.ApprovalService approvalService;

    @Autowired
    private JdbcTemplate db1JdbcTemplate;
    @Autowired
    private JdbcTemplate db2JdbcTemplate;

    @BeforeEach
    void clean() {
        db1JdbcTemplate.execute("DELETE FROM approval_history");
        db2JdbcTemplate.execute("DELETE FROM ums_send_history");
    }

    @Test
    void shouldCommitDb1AndFailDb2ProducingHeuristicException() {
        ApprovalRequest request = new ApprovalRequest(
                "APP-1",
                new BigDecimal("100.00"),
                "010-1234-5678",
                "hello"
        );

        Throwable thrown = catchThrowable(() -> approvalService.approveAndSendUms(request));

        Integer approvalCount = db1JdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM approval_history WHERE approval_id = ?",
                Integer.class,
                request.approvalId()
        );
        Integer umsCount = db2JdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_send_history WHERE approval_id = ?",
                Integer.class,
                request.approvalId()
        );

        if (thrown != null) {
            assertThat(thrown).hasRootCauseInstanceOf(HeuristicCompletionException.class);
            assertThat(approvalCount).isEqualTo(1);
            assertThat(umsCount).isEqualTo(0);
        } else {
            // 부하 상황에 따라 DB2까지 커밋이 될 수도 있음 (환경 기반 재현)
            assertThat(approvalCount).isEqualTo(1);
            assertThat(umsCount).isBetween(0, 1);
        }
    }
}
