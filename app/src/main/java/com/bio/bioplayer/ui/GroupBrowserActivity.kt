package com.bio.bioplayer.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bio.bioplayer.adapter.NodeAdapter
import com.bio.bioplayer.databinding.ActivityGroupBrowserBinding
import com.bio.bioplayer.model.Group
import com.bio.bioplayer.model.SourceType
import com.bio.bioplayer.parser.PlaylistFormat
import com.bio.bioplayer.parser.PlaylistParser
import com.bio.bioplayer.util.ContentFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bio.bioplayer.util.GroupHolder

class GroupBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP = "extra_group"
        // ใส่ 2 ตัวนี้เฉพาะตอนเปิดจากหน้ารวม (MainActivity) เพื่อให้รู้ว่าจะดึงข้อมูลใหม่จากไหน
        // หน้าย่อย (openSubGroup) จะไม่ส่งมา ปุ่มรีเฟรชเลยโชว์เฉพาะหน้าแรกของ playlist เท่านั้น
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_SOURCE = "extra_source"
        // ฟอร์แมตไฟล์ต้นทาง (JSON/M3U) - ใช้ตัดสินใจว่าลิงก์ youtube ต้องแปลงเป็น /watch ไหม (ดู PlayerActivity)
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_GROUP_KEY = "extra_group_key"
    }

    private lateinit var binding: ActivityGroupBrowserBinding

    // กำหนดค่าเริ่มต้นเป็น Grid ตามที่คุณตั้งไว้
    private var isGridView = true
    private lateinit var nodeAdapter: NodeAdapter
    private lateinit var currentGroup: Group
    private var format: PlaylistFormat = PlaylistFormat.JSON

    // แนวตั้ง 2 คอลัมน์ / แนวนอน 4 คอลัมน์
    private fun gridSpanCount(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val group = intent.getStringExtra(EXTRA_GROUP_KEY)?.let { GroupHolder.get(it) } ?: return finish()
        binding.groupTitle.text = group.name
        intent.getStringExtra(EXTRA_FORMAT)?.let { format = PlaylistFormat.valueOf(it) }
        // ถ้ากำลังค้นหาอยู่ (searchEditText โชว์) ให้ปิดช่องค้นหาก่อน ไม่งั้นค่อยออกจากหน้านี้
        binding.btnBack.setOnClickListener {
            if (binding.searchEditText.visibility == View.VISIBLE) closeSearch() else finish()
        }
        binding.btnSearchIcon.setOnClickListener { openSearch() }

        isGridView = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("grid", true)
        binding.recyclerView.layoutManager =
            if (isGridView) GridLayoutManager(this, gridSpanCount()) else LinearLayoutManager(this)
        bindGroup(group)

        // ปุ่มรีเฟรช: โชว์เฉพาะตอนมี source ส่งมา (หน้าแรกของ playlist ที่เปิดจากหน้ารวม)
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)
        val source = intent.getStringExtra(EXTRA_SOURCE)
        if (!sourceType.isNullOrBlank() && !source.isNullOrBlank()) {
            binding.btnRefresh.visibility = View.VISIBLE
            binding.btnRefresh.setOnClickListener { refreshFromSource(SourceType.valueOf(sourceType), source) }
        }

        // ระบบสลับมุมมอง
        binding.btnToggleLayout.setOnClickListener {
            isGridView = !isGridView
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("grid", isGridView).apply()
            nodeAdapter.isGrid = isGridView
            if (isGridView) {
                binding.recyclerView.layoutManager = GridLayoutManager(this, gridSpanCount())
            } else {
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
            }
            binding.recyclerView.adapter = nodeAdapter // รีเฟรช Adapter เพื่อเปลี่ยน Layout
        }

        // ระบบค้นหา
        binding.searchEditText.addTextChangedListener { text ->
            nodeAdapter.filter(text.toString())
        }
    }

    // สร้าง/แทนที่ adapter จากข้อมูล group ปัจจุบัน - ใช้ตอนเปิดหน้าครั้งแรกและตอนกดรีเฟรช
    private fun bindGroup(group: Group) {
        currentGroup = group
        if (group.groups.isNotEmpty()) {
            val names = group.groups.map { it.name } + group.stations.map { it.name }
            val images = group.groups.map { it.image } + group.stations.map { it.image }
            val subGroupCount = group.groups.size

            nodeAdapter = NodeAdapter(names, images) { position ->
                if (position < subGroupCount) {
                    openSubGroup(group.groups[position])
                } else {
                    playStation(group.stations[position - subGroupCount])
                }
            }
        } else {
            val names = group.stations.map { it.name }
            val images = group.stations.map { it.image }
            nodeAdapter = NodeAdapter(names, images) { position ->
                playStation(group.stations[position])
            }
        }

        nodeAdapter.isGrid = isGridView
        binding.recyclerView.adapter = nodeAdapter
    }

    // ดึงข้อมูล playlist จาก source ใหม่แล้วรีเฟรชหน้าจอ
    private fun refreshFromSource(sourceType: SourceType, source: String) {
        Toast.makeText(this, "กำลังรีเฟรช...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (sourceType == SourceType.URL) ContentFetcher.fetchUrl(source)
                    else ContentFetcher.fetchUri(contentResolver, Uri.parse(source))
                }
                val (root, refreshedFormat) = PlaylistParser.parseDetailed(content)
                format = refreshedFormat
                bindGroup(root.copy(name = currentGroup.name))
                Toast.makeText(this@GroupBrowserActivity, "รีเฟรชแล้ว", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@GroupBrowserActivity, "รีเฟรชไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ปรับจำนวนคอลัมน์เมื่อหมุนจอ (Activity ตั้ง configChanges ไว้ onCreate จะไม่ทำงานซ้ำ)
        (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount = gridSpanCount()
    }

    override fun onDestroy() {
        if (isFinishing) intent.getStringExtra(EXTRA_GROUP_KEY)?.let { GroupHolder.remove(it) }
        super.onDestroy()
    }

    // เปิดโหมดค้นหา: ซ่อนชื่อหมวดหมู่/ไอคอนแว่นขยาย/ไอคอนสลับมุมมอง แล้วโชว์ช่องพิมพ์แทนแบบเต็มพื้นที่
    private fun openSearch() {
        binding.groupTitle.visibility = View.GONE
        binding.btnSearchIcon.visibility = View.GONE
        binding.btnToggleLayout.visibility = View.GONE
        binding.btnRefresh.visibility = View.GONE
        binding.searchEditText.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    // ปิดโหมดค้นหา: กลับไปแสดงแถบปกติ
    private fun closeSearch() {
        binding.searchEditText.setText("")
        binding.searchEditText.visibility = View.GONE
        binding.groupTitle.visibility = View.VISIBLE
        binding.btnSearchIcon.visibility = View.VISIBLE
        binding.btnToggleLayout.visibility = View.VISIBLE
        // ปุ่มรีเฟรชกลับมาโชว์เฉพาะตอนหน้านี้มี source (นั่นคือปุ่มมันเคยถูกตั้ง visible ไว้ตั้งแต่ onCreate)
        if (!intent.getStringExtra(EXTRA_SOURCE).isNullOrBlank()) {
            binding.btnRefresh.visibility = View.VISIBLE
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun openSubGroup(sub: Group) {
        if (sub.groups.isNotEmpty() || sub.stations.isNotEmpty()) {
            startActivity(
                Intent(this, GroupBrowserActivity::class.java)
                    .putExtra(EXTRA_GROUP_KEY, GroupHolder.put(sub))
                    .putExtra(EXTRA_FORMAT, format.name) // สืบทอดฟอร์แมตจากหน้าปัจจุบัน (เนื้อหามาจากไฟล์เดิม)
            )
            return
        }

        if (sub.url.isBlank()) {
            Toast.makeText(this, "หมวดหมู่นี้ไม่มีเนื้อหา", Toast.LENGTH_SHORT).show()
            return
        }

        // This node is just a stub (name/image/url, no inline content) - the real
        // groups/stations live at `sub.url` and need to be fetched on demand.
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { ContentFetcher.fetchUrl(sub.url) }
                val (fetched, fetchedFormat) = PlaylistParser.parseDetailed(content)
                val merged = fetched.copy(
                    name = sub.name.ifBlank { fetched.name },
                    image = sub.image.ifBlank { fetched.image }
                )
                if (merged.groups.isEmpty() && merged.stations.isEmpty()) {
                    Toast.makeText(this@GroupBrowserActivity, "หมวดหมู่นี้ไม่มีเนื้อหา", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(this@GroupBrowserActivity, GroupBrowserActivity::class.java)
                            .putExtra(EXTRA_GROUP_KEY, GroupHolder.put(merged))
                            .putExtra(EXTRA_FORMAT, fetchedFormat.name) // sub.url อาจชี้ไปไฟล์ต่างฟอร์แมตจากไฟล์เดิม
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this@GroupBrowserActivity, "โหลดหมวดหมู่ไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playStation(station: com.bio.bioplayer.model.Station) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, station.url)
                .putExtra(PlayerActivity.EXTRA_TITLE, station.name)
                .putExtra(PlayerActivity.EXTRA_USER_AGENT, station.userAgent)
                .putExtra(PlayerActivity.EXTRA_REFERER, station.referer)
                .putExtra(PlayerActivity.EXTRA_IS_HOST, station.isHost)
                .putExtra(PlayerActivity.EXTRA_EMBED, station.embed)
                .putExtra(PlayerActivity.EXTRA_DESKTOP, station.desktop)
                .putExtra(PlayerActivity.EXTRA_HEADERS, HashMap(station.headers))
                .putExtra(PlayerActivity.EXTRA_SOURCE_FORMAT, format.name)
        )
    }
}
