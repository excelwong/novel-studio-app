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

/** 第 4 页：人物关系（左列表 + 右 7 字段表单）。 */
class RelationshipsFragment : Fragment() {

    private lateinit var adapter: SimpleListAdapter
    private lateinit var form: MutableMap<String, android.widget.EditText>
    private var currentIndex: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_editor_list, container, false)
        v.findViewById<TextView>(R.id.lbl_list).text = "人物关系列表"
        v.findViewById<TextView>(R.id.lbl_form).text =
            "关系档案（甲方/乙方/关系类型/性质/双方称谓）"
        v.findViewById<Button>(R.id.btn_new).text = "新增关系"
        v.findViewById<Button>(R.id.btn_save).text = "保存关系"
        v.findViewById<Button>(R.id.btn_del).text = "删除所选"

        val rv = v.findViewById<RecyclerView>(R.id.rv_list)
        adapter = SimpleListAdapter { pos ->
            currentIndex = pos
            val rec = Store.rels.getOrNull(pos)
            FormBuilder.fill(form, rec)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        form = FormBuilder.build(
            v.findViewById(R.id.form_container), Repo.REL_FIELDS.toList(),
            minLinesOf = { f -> if (f == "备注") 3 else 1 })

        v.findViewById<Button>(R.id.btn_new).setOnClickListener {
            currentIndex = -1
            FormBuilder.clear(form)
        }
        v.findViewById<Button>(R.id.btn_save).setOnClickListener { saveRel() }
        v.findViewById<Button>(R.id.btn_del).setOnClickListener { delRel() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Changes.version.collect { if (isAdded) refresh() }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        adapter.set(Store.rels.map {
            "${it["甲方"] ?: "?"} — ${it["乙方"] ?: "?"} ${it["关系类型"] ?: ""}"
        })
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return false
        }
        return true
    }

    private fun saveRel() {
        if (!ensureProject()) return
        val rec = FormBuilder.readAll(form)
        if (rec["甲方"]!!.isBlank() || rec["乙方"]!!.isBlank()) {
            toast("甲方、乙方不能为空。")
            return
        }
        if (currentIndex in Store.rels.indices) {
            Store.rels[currentIndex] = rec
        } else {
            Store.rels.add(rec)
            currentIndex = Store.rels.size - 1
        }
        Repo.saveRels()
        refresh()
        if (currentIndex < adapter.items.size) adapter.selected = currentIndex
        toast("关系『${rec["甲方"]} — ${rec["乙方"]}』已保存（共 ${Store.rels.size} 条）。")
    }

    private fun delRel() {
        val pos = adapter.selected
        if (pos < 0 || pos >= Store.rels.size) { toast("请先选择关系。"); return }
        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("删除所选关系？")
            .setPositiveButton("删除") { _, _ ->
                Store.rels.removeAt(pos)
                Repo.saveRels()
                currentIndex = -1
                FormBuilder.clear(form)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
