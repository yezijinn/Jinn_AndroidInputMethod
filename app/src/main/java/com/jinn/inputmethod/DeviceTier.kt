package com.jinn.inputmethod

import android.app.ActivityManager
import android.content.Context

/**
 * 设备内存档位判定（跨批契约 C-3）：低内存档将图片缓存折半、解码线程收敛为 1。
 * 消费方：图库缓存上限、缩略图缓存（图库与剪贴板各一处）、缩略图线程数、诊断头。
 *
 * 动机：低配机型上大缓冲是 LMK（低内存杀手）高危点。本入口只缩放图片缓存 ——
 * 展开扫描 512MB / 包 256MB / 词库 64MB / 解密窗口 48MB / 下载 64MB 均不接入，仍归各自调用方。
 *
 * 设计约束：
 *  - **只有两档**：`ActivityManager.isLowRamDevice()` 为真取 0.5×，否则 1×。
 *    不做连续系数（按可用内存比例缩放会让同一份配置在不同机型上表现漂移，无法复现故障）；
 *  - **只对 ≥ [SCALE_THRESHOLD_BYTES] 的项生效**：当前三处调用（缩略图缓存 12MB ×2、图库缓存 60MB）
 *    都超过阈值；更小的零星预算不接本入口，避免无收益的折半；
 *  - **高配一侧不放宽**：非低配直接返回原值，本入口从不调大任何预算；
 *  - 不碰 Binder 事务上限与剪贴板解密窗口：前者受系统 1MB 事务约束，后者按 GCM 单次解密的代价自设，
 *    都跟设备档位无关，调用方不得把它们接进来。
 *
 * 判据来源是系统自报（`ro.config.low_ram` / `ActivityManager.isLowRamDevice`），
 * 比按 `Runtime.maxMemory()` 猜更稳定：后者受 `android:largeHeap` 与厂商调参影响，同一档机型能差一倍。
 */
object DeviceTier {

    /**
     * 参与降档的最小预算。
     *
     * ⚠ 这个阈值必须与**实际消费点**对得上：图片缓存的消费点只有三处 —— 缩略图缓存 12MB ×2、
     * 图库暂存 60MB。早先定在 256MB，三处全部低于阈值 ⇒ 折半永不生效，
     * 而各消费点的 KDoc 都写着「低内存档按 DeviceTier 折半」，等于声明了没落地。
     * 取 8MB：让 12/60MB 在低配机上真的折半（6MB / 30MB），同时不至于把
     * 小于 8MB 的零星预算也砍一刀。
     */
    const val SCALE_THRESHOLD_BYTES = 8L * 1024 * 1024

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
     * @return `bytes` 本身（1×，非低配或低于阈值时）或 0.5×（低配且 ≥ 阈值时）；
     *         入参 ≤ 0 视为无效配置，原样返回（不放大、不报错 —— 上限校验由调用方负责）。
     */
    fun budget(context: Context, bytes: Long): Long {
        if (bytes <= 0 || bytes < SCALE_THRESHOLD_BYTES) return bytes
        return if (isLowRam(context)) bytes * LOW_RAM_NUM / LOW_RAM_DEN else bytes
    }

    /**
     * 诊断头一行：档位与系数。消费方取值后把它写进日志，
     * 事后才能从日志判断某次失败是不是低配档位造成的。
     */
    fun describe(context: Context): String =
        if (isLowRam(context)) "低内存档(0.5×, ≥${SCALE_THRESHOLD_BYTES / (1024 * 1024)}MB 生效)" else "标准档(1×)"
}
