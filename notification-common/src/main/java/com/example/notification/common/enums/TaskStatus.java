package com.example.notification.common.enums;

/**
 * 任务状态枚举
 *
 * 状态流转：
 * PENDING -> PROCESSING -> SUCCESS (成功)
 * PENDING -> PROCESSING -> FAILED (临时失败，可重试) -> PENDING
 * PENDING -> PROCESSING -> FAILED (重试耗尽，最终失败)
 */
public enum TaskStatus {

    /**
     * 待处理 - 任务已创建，等待Worker处理
     */
    PENDING,

    /**
     * 处理中 - Worker正在投递通知
     */
    PROCESSING,

    /**
     * 成功 - 通知投递成功（最终状态）
     */
    SUCCESS,

    /**
     * 失败 - 通知投递失败（可能是临时失败等待重试，或最终失败）
     */
    FAILED;

    /**
     * 是否为最终状态
     */
    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }

    /**
     * 是否可以被处理
     */
    public boolean canProcess() {
        return this == PENDING;
    }
}
