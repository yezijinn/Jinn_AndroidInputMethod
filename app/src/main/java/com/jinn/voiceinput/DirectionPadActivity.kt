package com.jinn.voiceinput

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 方向控制面板（功能面板「方向」按钮打开）。
 *
 * 提供编辑光标控制：上下左右移动、空格、回车、一键到行首/行末。
 * 由于面板是独立 Activity，按键动作通过广播转发给常驻的 [JinnIme]，
 * 由 IME 用当前 InputConnection 执行（面板关闭后 IME 键盘仍在）。
 *
 * 隐私：本页不含任何文本内容，仅控制按键。
 */
class DirectionPadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.i(TAG, "onCreate: 方向控制面板启动")
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        val title = TextView(this).apply {
            text = getString(R.string.direction_pad_title)
            textSize = 18f
            setTextColor(Color.parseColor("#ECEEF2"))
            gravity = Gravity.CENTER
        }
        root.addView(title, lp())

        val desc = TextView(this).apply {
            text = getString(R.string.direction_pad_desc)
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(desc, lp())

        // 方向区：上 / 左 中 右 / 下
        val padRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        padRow1.addView(key("上", DirectionAction.UP), keyLp())
        root.addView(padRow1, lp())

        val padRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        padRow2.addView(key("左", DirectionAction.LEFT), keyLp())
        padRow2.addView(key("下", DirectionAction.DOWN), keyLp())
        padRow2.addView(key("右", DirectionAction.RIGHT), keyLp())
        root.addView(padRow2, lp())

        // 行首 / 行末
        val lineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lineRow.addView(key("行首", DirectionAction.LINE_START), keyLp())
        lineRow.addView(key("行末", DirectionAction.LINE_END), keyLp())
        root.addView(lineRow, lp())

        // 空格 / 回车
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow.addView(key("空格", DirectionAction.SPACE), wideKeyLp())
        actionRow.addView(key("回车", DirectionAction.ENTER), wideKeyLp())
        root.addView(actionRow, lp())

        val hint = TextView(this).apply {
            text = getString(R.string.direction_pad_close_hint)
            textSize = 11f
            setTextColor(Color.parseColor("#F5A623"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(hint, lp())
        return root
    }

    private fun key(label: String, action: DirectionAction): View {
        val btn = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ECEEF2"))
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setPadding(dp(8), dp(16), dp(8), dp(16))
            isClickable = true
            setOnClickListener {
                Diagnostics.i(TAG, "方向按键: $action")
                runCatching {
                    sendBroadcast(Intent(DirectionPadActivity.ACTION_DIRECTION)
                        .setPackage(packageName)
                        .putExtra(DirectionPadActivity.EXTRA_ACTION, action.ordinal))
                    Toast.makeText(this@DirectionPadActivity, label, Toast.LENGTH_SHORT).show()
                }.onFailure { Diagnostics.w(TAG, "发送方向按键失败: ${it.message}") }
            }
        }
        return btn
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun keyLp() = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(6)
        marginEnd = dp(6)
    }

    private fun wideKeyLp() = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(6)
        marginEnd = dp(6)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 方向动作（与 IME 侧 [JinnIme] 的解析保持一致） */
    enum class DirectionAction { UP, DOWN, LEFT, RIGHT, LINE_START, LINE_END, SPACE, ENTER }

    companion object {
        const val TAG = "DirectionPad"
        const val ACTION_DIRECTION = "com.jinn.voiceinput.action.DIRECTION"
        const val EXTRA_ACTION = "direction_action"
    }
}
