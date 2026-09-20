package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.InitialReviewReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 初审报告 Mapper（append-only：只插入与查询，不更新、不删除）。
 *
 * <p>报告不得被覆盖：一旦复核发现"报告与数据不一致"，需要能取到当时那一版。
 */
@Mapper
public interface InitialReviewReportMapper extends BaseMapper<InitialReviewReportEntity> {

    /** 最新一版报告 */
    @Select("""
            SELECT * FROM initial_review_report
             WHERE case_id = #{caseId}
             ORDER BY revision_no DESC
             LIMIT 1
            """)
    InitialReviewReportEntity findLatest(@Param("caseId") Long caseId);

    /** 下一个修订号（报告 append-only，修订号连续递增） */
    @Select("""
            SELECT COALESCE(MAX(revision_no), 0) + 1 FROM initial_review_report
             WHERE case_id = #{caseId}
            """)
    int nextRevisionNo(@Param("caseId") Long caseId);
}
