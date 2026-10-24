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

/** 第 3 页：人物档案（左列表 + 右 14 字段表单，表单程序化生成）。 */
class CharactersFragment : Fragment() {

    private lateinit var adapter: SimpleListAdapter
    private lateinit var form: MutableMap<String, android.widget.EditText>
    private var currentName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_editor_list, container, false)
        v.findViewById<TextView>(R.id.lbl_list).text = "人物列表"
        v.findViewById<TextView>(R.id.lbl_form).text =
            "人物档案（★ 性格/语言特点/动机 对生成质量影响最大）"
        v.findViewById<Button>(R.id.btn_new).text = "新增人物"
        v.findViewById<Button>(R.id.btn_save).text = "保存人物"
        v.findViewById<Button>(R.id.btn_del).text = "删除所选"

        val rv = v.findViewById<RecyclerView>(R.id.rv_list)
        adapter = SimpleListAdapter { pos ->
            val name = adapter.items.getOrNull(pos) ?: return@SimpleListAdapter
            currentName = name
            val rec = Store.chars.firstOrNull { it["姓名"] == name }
            FormBuilder.fill(form, rec)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        form = FormBuilder.build(
            v.findViewById(R.id.form_container), Repo.CHAR_FIELDS.toList(),
            minLinesOf = { f -> if (f in setOf("姓名", "性别", "年龄", "身高体型")) 1 else 3 })

        v.findViewById<Button>(R.id.btn_new).setOnClickListener {
            currentName = null
            FormBuilder.clear(form)
        }
        v.findViewById<Button>(R.id.btn_save).setOnClickListener { saveChar() }
        v.findViewById<Button>(R.id.btn_del).setOnClickListener { delChar() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Changes.version.collect { if (isAdded) refresh() }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        adapter.set(Store.chars.map { it["姓名"] ?: "未命名" })
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return false
        }
        return true
    }

    private fun saveChar() {
        if (!ensureProject()) return
        val data = FormBuilder.readAll(form)
        data["姓名"] = data["姓名"]!!.split("\n").joinToString(" ").trim()
        if (data["姓名"]!!.isBlank()) { toast("姓名不能为空。"); return }
        if (currentName != null && currentName != data["姓名"]) {
            Store.chars.removeAll { it["姓名"] == currentName }
        }
        val idx = Store.chars.indexOfFirst { it["姓名"] == data["姓名"] }
        if (idx >= 0) Store.chars[idx] = data else Store.chars.add(data)
        Repo.saveChars()
        currentName = data["姓名"]
        refresh()
        val pos = adapter.items.indexOf(data["姓名"])
        if (pos >= 0) adapter.selected = pos
        toast("人物『${data["姓名"]}』已保存（共 ${Store.chars.size} 人）。")
    }

    private fun delChar() {
        val pos = adapter.selected
        if (pos < 0 || pos >= adapter.items.size) { toast("请先选择人物。"); return }
        val name = adapter.items[pos]
        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("删除人物『$name』？")
            .setPositiveButton("删除") { _, _ ->
                Store.chars.removeAll { it["姓名"] == name }
                Repo.saveChars()
                currentName = null
                FormBuilder.clear(form)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
