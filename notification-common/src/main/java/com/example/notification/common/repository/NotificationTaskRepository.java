package com.example.notification.common.repository;

import com.example.notification.common.entity.NotificationTask;
import com.example.notification.common.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通知任务数据访问层
 */
@Repository
public interface NotificationTaskRepository extends JpaRepository<NotificationTask, Long> {

    /**
     * 根据taskId查询任务
     */
    Optional<NotificationTask> findByTaskId(String taskId);

    /**
     * 查询待处理的任务（用于Worker拉取）
     * 状态为PENDING且重试时间已到
     */
    @Query("SELECT t FROM NotificationTask t WHERE t.status = :status " +
           "AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now) " +
           "ORDER BY t.createdAt ASC")
    List<NotificationTask> findPendingTasks(
        @Param("status") TaskStatus status,
        @Param("now") LocalDateTime now,
        org.springframework.data.domain.Pageable pageable
    );

    /**
     * 乐观锁更新任务状态（防止并发问题）
     */
    @Modifying
    @Query("UPDATE NotificationTask t SET t.status = :newStatus, t.updatedAt = :now " +
           "WHERE t.id = :id AND t.status = :expectedStatus")
    int updateStatusWithLock(
        @Param("id") Long id,
        @Param("expectedStatus") TaskStatus expectedStatus,
        @Param("newStatus") TaskStatus newStatus,
        @Param("now") LocalDateTime now
    );

    /**
     * 查询超时的处理中任务（用于恢复）
     * 如果任务处于PROCESSING状态超过指定时间，可能Worker崩溃了
     */
    @Query("SELECT t FROM NotificationTask t WHERE t.status = 'PROCESSING' " +
           "AND t.updatedAt < :threshold")
    List<NotificationTask> findStuckTasks(@Param("threshold") LocalDateTime threshold);

    /**
     * 按来源系统统计任务数量
     */
    @Query("SELECT t.sourceSystem, t.status, COUNT(t) FROM NotificationTask t " +
           "GROUP BY t.sourceSystem, t.status")
    List<Object[]> countBySourceSystemAndStatus();

    /**
     * 按状态统计任务数量
     */
    long countByStatus(TaskStatus status);
}
