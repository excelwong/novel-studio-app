package com.example.novelstudio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** 第 5 页：剧情大纲（左列表 + 右 9 字段表单；保存后自动排序重编号）。 */
class OutlineFragment : Fragment() {

    private lateinit var adapter: SimpleListAdapter
    private lateinit var form: MutableMap<String, android.widget.EditText>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_editor_list, container, false)
        v.findViewById<TextView>(R.id.lbl_list).text = "章节大纲列表"
        v.findViewById<TextView>(R.id.lbl_form).text =
            "逐章大纲（章节/标题/时间/人物/地点/事件/冲突/钩子）"
        v.findViewById<Button>(R.id.btn_new).text = "新增章节"
        v.findViewById<Button>(R.id.btn_save).text = "保存章节"
        v.findViewById<Button>(R.id.btn_del).text = "删除所选"

        val rv = v.findViewById<RecyclerView>(R.id.rv_list)
        adapter = SimpleListAdapter { pos ->
            val rec = Store.outline.getOrNull(pos)
            FormBuilder.fill(form, rec)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        form = FormBuilder.build(
            v.findViewById(R.id.form_container), Repo.OUTLINE_FIELDS.toList(),
            minLinesOf = { f ->
                if (f in setOf("事件", "冲突/看点", "结尾钩子", "备注", "标题")) 3 else 1
            })

        v.findViewById<Button>(R.id.btn_new).setOnClickListener {
            val next = (Store.outline.maxOfOrNull { it["章节"]?.toIntOrNull() ?: 0 } ?: 0) + 1
            FormBuilder.clear(form)
            form["章节"]?.setText(next.toString())
        }
        v.findViewById<Button>(R.id.btn_save).setOnClickListener { saveOutline() }
        v.findViewById<Button>(R.id.btn_del).setOnClickListener { delOutline() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Changes.version.collect { if (isAdded) refresh() }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        adapter.set(Store.outline.map { "第${it["章节"]}章 ${it["标题"] ?: ""}" })
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return false
        }
        return true
    }

    private fun saveOutline() {
        if (!ensureProject()) return
        val rec = FormBuilder.readAll(form)
        val num = Regex("\\d+").find(rec["章节"] ?: "")?.value?.toIntOrNull()
        if (num == null) { toast("章节号必须为数字。"); return }
        rec["章节"] = num.toString()
        val idx = Store.outline.indexOfFirst { (it["章节"] ?: "").toIntOrNull() == num }
        if (idx >= 0) Store.outline[idx] = rec else Store.outline.add(rec)
        Repo.saveOutline()   // 排序 + 重编号
        Changes.bump()       // 通知摘要/正文页刷新章节下拉
        refresh()
        val pos = Store.outline.indexOfFirst { (it["章节"] ?: "").toIntOrNull() == num }
        if (pos >= 0) { adapter.selected = pos; FormBuilder.fill(form, Store.outline[pos]) }
        toast("第 $num 章大纲已保存（共 ${Store.outline.size} 章）。")
    }

    private fun delOutline() {
        val pos = adapter.selected
        if (pos < 0 || pos >= Store.outline.size) { toast("请先选择章节大纲。"); return }
        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("删除所选章节大纲？")
            .setPositiveButton("删除") { _, _ ->
                Store.outline.removeAt(pos)
                Repo.saveOutline()
                Changes.bump()
                FormBuilder.clear(form)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
