package com.jinn.inputmethod

import android.app.ActivityManager
import android.content.Context

/**
 * 设备内存档位判定（跨批契约 C-3，供批次 1 的容器容量与批次 4 的图库上限消费）。
 *
 * 动机：全仓的大缓冲上限（展开扫描 512MB / 包 256MB / 词库 64MB / 解密窗口 48MB / 下载 64MB）
 * 原先都是编译期常量，在低配机型上这些就是 LMK（低内存杀手）的高危点。
 *
 * 设计约束（按任务书）：
 *  - **只有两档**：`ActivityManager.isLowRamDevice()` 为真取 0.5×，否则 1×。
 *    不做连续系数（按可用内存比例缩放会让同一份配置在不同机型上表现漂移，无法复现故障）；
 *  - **只对 ≥ [SCALE_THRESHOLD_BYTES] 的项生效**：小预算（几十 MB 的下载/解密窗口）本来就留了余量，
 *    再折半只会让正常功能受限 —— 缩放它们是无收益的风险；
 *  - **高配一侧不放宽**：本入口永远返回 `min(原值, 折算值)`，不存在「高配调大」这条路；
 *  - **不碰 Binder 事务上限与剪贴板解密窗口**：那两项的约束来自系统（1MB 事务 / GCM 单次解密），
 *    与设备档位无关，调用方不得把它们接进来。
 *
 * 判据来源是系统自报（`ro.config.low_ram` / `ActivityManager.isLowRamDevice`），
 * 比按 `Runtime.maxMemory()` 猜更稳定：后者受 `android:largeHeap` 与厂商调参影响，同一档机型能差一倍。
 */
object DeviceTier {

    /**
     * 参与降档的最小预算：≥256MB 的项才折算。
     *
     * 256MB 这个数取自任务书（低配机型上最容易触发 LMK 的正是这一类一次性大缓冲）。
     */
    const val SCALE_THRESHOLD_BYTES = 256L * 1024 * 1024

    /** 低内存机型系数：0.5×（分子分母各留一份，避免浮点误差渗进字节数） */
    private const val LOW_RAM_NUM = 1L
    private const val LOW_RAM_DEN = 2L

    /**
     * 是否低内存设备。
     *
     * 每次现读：`isLowRamDevice()` 在框架内是一次字段读（无 Binder），而进程存活期间该值不会变，
     * 缓存它反而要处理「测试注入的 Context」这类边界 —— 不值当。
     */
    fun isLowRam(context: Context): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .isLowRamDevice
    }.getOrDefault(false)

    /**
     * 把一份内存预算按档位折算。
     *
     * @return `bytes` 本身（≥1×，非低配或低于阈值时）或 0.5×（低配且 ≥ 阈值时）；
     *         入参 ≤ 0 视为无效配置，原样返回（不放大、不报错 —— 上限校验由调用方负责）。
     */
    fun budget(context: Context, bytes: Long): Long {
        if (bytes <= 0 || bytes < SCALE_THRESHOLD_BYTES) return bytes
        return if (isLowRam(context)) bytes * LOW_RAM_NUM / LOW_RAM_DEN else bytes
    }

    /**
     * 诊断头一行：档位与系数。消费方取值后把它写进日志（「生效值写诊断头」的落点），
     * 事后才能从日志判断某次失败是不是低配档位造成的。
     */
    fun describe(context: Context): String =
        if (isLowRam(context)) "低内存档(0.5×, ≥${SCALE_THRESHOLD_BYTES / (1024 * 1024)}MB 生效)" else "标准档(1×)"
}
