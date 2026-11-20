package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

public interface RunBudgetMapper {
    /** run 预算聚合：budget_amount / reserved_amount / settled_amount */
    Optional<RunBudgetRow> find(@Param("tenantId") String tenantId, @Param("runId") String runId);

    class RunBudgetRow {
        private String runId;
        private java.math.BigDecimal budgetAmount;
        private java.math.BigDecimal reservedAmount;
        private java.math.BigDecimal settledAmount;
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public java.math.BigDecimal getBudgetAmount() { return budgetAmount; }
        public void setBudgetAmount(java.math.BigDecimal v) { this.budgetAmount = v; }
        public java.math.BigDecimal getReservedAmount() { return reservedAmount; }
        public void setReservedAmount(java.math.BigDecimal v) { this.reservedAmount = v; }
        public java.math.BigDecimal getSettledAmount() { return settledAmount; }
        public void setSettledAmount(java.math.BigDecimal v) { this.settledAmount = v; }
    }
}
