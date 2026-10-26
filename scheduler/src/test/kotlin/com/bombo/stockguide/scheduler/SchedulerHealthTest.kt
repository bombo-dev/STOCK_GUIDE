package com.bombo.stockguide.scheduler

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "STOCK_GUIDE_DB_URL=jdbc:postgresql://localhost:1/unreachable",
        "STOCK_GUIDE_DB_USER=test",
        "STOCK_GUIDE_DB_PASSWORD=test",
    ],
)
@AutoConfigureMockMvc
@DisplayName("SchedulerApplication 헬스체크")
class SchedulerHealthTest(@Autowired private val mockMvc: MockMvc) {
    @Nested
    @DisplayName("DB에 접속할 수 없을 때")
    inner class WhenDatabaseIsUnreachable {
        @Test
        @DisplayName("liveness는 UP이다")
        fun livenessIsUp() {
            mockMvc.get("/actuator/health/liveness").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
        }

        @Test
        @DisplayName("readiness는 DB를 포함해 DOWN이다")
        fun readinessIsDown() {
            mockMvc.get("/actuator/health/readiness").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.status") { value("DOWN") }
            }
        }
    }
}
