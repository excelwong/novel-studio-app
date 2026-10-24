package com.example.novelstudio

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 项目级共享状态变化通知（素材/摘要/大纲等落盘后自增，各页面据此刷新）。 */
object Changes {
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> get() = _version
    fun bump() { _version.value = _version.value + 1 }
}

/** 应用级 LLM 任务运行器：同一时刻只允许一个任务；abort 供流式生成中止。 */
object Jobs {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var currentAbort: LlmClient.AbortFlag? = null
        private set

    /** 返回 false 表示已有任务在跑。work 完成后在主线程回调。 */
    fun run(taskName: String, work: suspend () -> Unit): Boolean {
        if (Store.isBusy()) return false
        Store.setBusy(true)
        Store.setStatus(taskName + "…（推理中，请耐心等待）")
        scope.launch {
            try {
                work()
                if (currentAbort?.aborted == true) {
                    Store.setStatus("已中止，已保留已生成的内容（可继续编辑后保存）。")
                } else {
                    Store.setStatus("完成。")
                }
            } catch (e: CancellationException) {
                Store.setStatus("已中止。")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(App.ctx, "调用失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                Store.setStatus("调用失败。请检查模型服务与 API 设置。")
            } finally {
                currentAbort = null
                Store.setBusy(false)
            }
        }
        return true
    }

    fun abort() {
        currentAbort?.set()
        Store.setStatus("正在中止…（已生成的部分会保留）")
    }
}

/** 全局 Application：存全局 Context 供 Toast 用。 */
class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
    }

    companion object {
        lateinit var ctx: android.content.Context
            private set
    }
}

/** 简单字符串列表适配器（带选中高亮）。 */
class SimpleListAdapter(
    private val onClick: (position: Int) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleListAdapter.VH>() {

    val items = mutableListOf<String>()
    var selected = -1

    fun set(list: List<String>, keepSelection: Boolean = false) {
        items.clear()
        items.addAll(list)
        if (!keepSelection) selected = -1
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = android.widget.TextView(parent.context)
        tv.setPadding(
            parent.context.resources.getDimensionPixelSize(R.dimen.list_item_pad),
            parent.context.resources.getDimensionPixelSize(R.dimen.list_item_pad),
            parent.context.resources.getDimensionPixelSize(R.dimen.list_item_pad),
            parent.context.resources.getDimensionPixelSize(R.dimen.list_item_pad))
        tv.textSize = 15f
        tv.setTextColor(0xFF000000.toInt())
        tv.setBackgroundResource(R.drawable.list_item_bg)
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = items[position]
        holder.tv.setBackgroundColor(
            if (position == selected) 0x332196F3 else 0x00000000)
        holder.tv.setOnClickListener {
            val old = selected
            selected = holder.bindingAdapterPosition
            if (old != selected) {
                notifyItemChanged(old)
                notifyItemChanged(selected)
            }
            onClick(selected)
        }
    }
}

/** 程序化构建「字段名 + 多行输入」表单（人物/关系/大纲三页共用）。 */
object FormBuilder {

    fun build(
        container: LinearLayout,
        fields: List<String>,
        minLinesOf: (String) -> Int = { 2 }
    ): MutableMap<String, EditText> {
        container.removeAllViews()
        val map = LinkedHashMap<String, EditText>()
        val ctx = container.context
        for (field in fields) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            row.gravity = Gravity.TOP

            val label = TextView(ctx)
            label.text = "$field："
            label.setTextColor(0xFF0D47A1.toInt())
            label.textSize = 14f
            label.setPadding(0, dp(ctx, 10), 0, 0)
            val lp = LinearLayout.LayoutParams(
                ctx.resources.getDimensionPixelSize(R.dimen.form_label_w),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            row.addView(label, lp)

            val et = EditText(ctx)
            et.hint = ""
            et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.gravity = Gravity.TOP
            et.minLines = minLinesOf(field)
            et.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            et.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(ctx, 4).toFloat()
                setStroke(1, 0xFFCCCCCC.toInt())
            }
            et.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            val etLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(et, etLp)
            map[field] = et
            container.addView(row)
        }
        return map
    }

    fun readAll(map: Map<String, EditText>): Rec {
        val r = Repo.emptyRec(map.keys.toTypedArray())
        map.forEach { (k, et) -> r[k] = et.text.toString().trim() }
        return r
    }

    fun fill(map: Map<String, EditText>, rec: Rec?) {
        map.forEach { (k, et) -> et.setText(rec?.get(k) ?: "") }
    }

    fun clear(map: Map<String, EditText>) = fill(map, null)

    fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

/** 无操作空实现，避免未绑定回调时 NPE。 */
object NoOp : View.OnClickListener {
    override fun onClick(v: View?) {}
}
