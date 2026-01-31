package com.example.notification.common.repository;

import com.example.notification.common.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通知日志数据访问层
 */
@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * 根据taskId查询投递日志
     */
    List<NotificationLog> findByTaskIdOrderByAttemptNumberAsc(String taskId);

    /**
     * 查询最近的投递日志
     */
    @Query("SELECT l FROM NotificationLog l WHERE l.taskId = :taskId ORDER BY l.createdAt DESC")
    List<NotificationLog> findRecentLogs(@Param("taskId") String taskId, org.springframework.data.domain.Pageable pageable);

    /**
     * 统计成功/失败次数
     */
    @Query("SELECT l.success, COUNT(l) FROM NotificationLog l GROUP BY l.success")
    List<Object[]> countBySuccess();
}
