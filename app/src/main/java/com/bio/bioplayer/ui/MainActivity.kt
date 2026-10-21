package com.bio.bioplayer.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bio.bioplayer.R
import com.bio.bioplayer.adapter.SavedPlaylistAdapter
import com.bio.bioplayer.databinding.ActivityMainBinding
import com.bio.bioplayer.databinding.DialogAddPlaylistBinding
import com.bio.bioplayer.model.SavedPlaylist
import com.bio.bioplayer.model.SourceType
import com.bio.bioplayer.parser.PlaylistParser
import com.bio.bioplayer.storage.SavedPlaylistsStore
import com.bio.bioplayer.util.ContentFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bio.bioplayer.util.GroupHolder
import android.provider.OpenableColumns
import com.bio.bioplayer.parser.PlaylistFormat

/**
 * "Lists" screen: shows saved playlists (name/author/image only - the
 * actual tree is re-fetched from `source` each time one is opened).
 * FAB opens "Add list from URL" / "Add list from file", matching the
 * reference app's add-list bottom sheet (QR / migrate / share are not
 * implemented in this MVP).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SavedPlaylistsStore
    private var isGridView = true // ค่าเริ่มต้น: แสดงแบบ grid 2 คอลัมน์ตามต้นแบบ

    // จำโหมด grid/list ไว้ข้ามการปิด-เปิดแอป
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val adapter = SavedPlaylistAdapter(
        onClick = { openPlaylist(it) },
        onLongClick = { showItemMenu(it) }
    )

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            // Keep read access across app restarts.
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fetchAndSave(SourceType.FILE, it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SavedPlaylistsStore(this)

        isGridView = prefs.getBoolean("grid", true)
        applyLayout()

        binding.btnToggleLayout.setOnClickListener {
            isGridView = !isGridView
            prefs.edit().putBoolean("grid", isGridView).apply()
            applyLayout()
        }

        binding.searchEditText.addTextChangedListener { text ->
            adapter.filter(text.toString())
        }
        binding.btnSearchIcon.setOnClickListener { openSearch() }

        binding.fabAdd.setOnClickListener { showAddOptions() }
    }

    // เปิดโหมดค้นหา: ซ่อนชื่อหน้า/ไอคอนแว่นขยาย/สลับมุมมอง/รีเฟรช แล้วโชว์ช่องพิมพ์แทนแบบเต็มพื้นที่
    private fun openSearch() {
        binding.pageTitle.visibility = View.GONE
        binding.btnSearchIcon.visibility = View.GONE
        binding.btnToggleLayout.visibility = View.GONE
        binding.searchEditText.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    // ปิดโหมดค้นหา: กลับไปแสดงแถบปกติ (เรียกตอนกดปุ่ม back ของระบบ)
    private fun closeSearch() {
        binding.searchEditText.setText("")
        binding.searchEditText.visibility = View.GONE
        binding.pageTitle.visibility = View.VISIBLE
        binding.btnSearchIcon.visibility = View.VISIBLE
        binding.btnToggleLayout.visibility = View.VISIBLE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    override fun onBackPressed() {
        if (binding.searchEditText.visibility == View.VISIBLE) closeSearch() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val saved = store.getAll()
        adapter.submit(saved)
        binding.emptyText.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddOptions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.add_playlist)
            .setItems(
                arrayOf(
                    getString(R.string.add_from_url),
                    getString(R.string.pick_file),
                    "Open Network Stream..."
                )
            ) { _, which ->
                when (which) {
                    0 -> showUrlDialog()
                    1 -> filePicker.launch(arrayOf("*/*"))
                    else -> showPlayLinkDialog()
                }
            }
            .show()
    }

    // เล่นลิงก์สตรีมเดี่ยวทันที ไม่บันทึกลงรายการ
    private fun showPlayLinkDialog() {
        val db = DialogAddPlaylistBinding.inflate(layoutInflater)
        db.urlInput.hint = "https://example.com/stream.m3u8"
        AlertDialog.Builder(this)
            .setTitle("Open Network Stream...")
            .setView(db.root)
            .setPositiveButton("Play") { _, _ ->
                val url = db.urlInput.text.toString().trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, url)
                            .putExtra(PlayerActivity.EXTRA_TITLE, url.substringBefore("|").substringBefore("?").substringAfterLast("/"))
                    )
                } else {
                    Toast.makeText(this, "ลิงก์ต้องขึ้นต้นด้วย http:// หรือ https://", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUrlDialog() {
        val db = DialogAddPlaylistBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_from_url)
            .setView(db.root)
            .setPositiveButton(R.string.load) { _, _ ->
                val url = db.urlInput.text.toString().trim()
                if (url.isNotEmpty()) fetchAndSave(SourceType.URL, url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchAndSave(sourceType: SourceType, source: String) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val (content, sourceName) = withContext(Dispatchers.IO) {
                    if (sourceType == SourceType.URL) {
                        ContentFetcher.fetchUrl(source) to source // ลิงก์ทั้งยวง
                    } else {
                        val uri = Uri.parse(source)
                        ContentFetcher.fetchUri(contentResolver, uri) to
                            (displayNameOf(uri) ?: Uri.decode(source).substringAfterLast("/")) // ชื่อไฟล์
                    }
                }
                val (root, format) = PlaylistParser.parseDetailed(content)
                // M3U ไม่มีชื่อ playlist ในเนื้อหา ใช้ชื่อไฟล์/ลิงก์เสมอ ส่วน JSON ใช้ชื่อในไฟล์ก่อน
                val displayName = if (format == PlaylistFormat.M3U) sourceName else root.name.ifBlank { sourceName }
                store.add(name = displayName, author = root.author, image = root.image, sourceType = sourceType, source = source)
                refreshList()
            } catch (e: Exception) {
                showLoadError(e)
            }
        }
    }

    private fun openPlaylist(p: SavedPlaylist) {
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (p.sourceType == SourceType.URL) ContentFetcher.fetchUrl(p.source)
                    else ContentFetcher.fetchUri(contentResolver, Uri.parse(p.source))
                }
                val (root, format) = PlaylistParser.parseDetailed(content)
                startActivity(
                    Intent(this@MainActivity, GroupBrowserActivity::class.java)
                        .putExtra(GroupBrowserActivity.EXTRA_GROUP_KEY, GroupHolder.put(root.copy(name = p.name)))
                        .putExtra(GroupBrowserActivity.EXTRA_SOURCE_TYPE, p.sourceType.name)
                        .putExtra(GroupBrowserActivity.EXTRA_SOURCE, p.source)
                        .putExtra(GroupBrowserActivity.EXTRA_FORMAT, format.name)
                )
            } catch (e: Exception) {
                showLoadError(e)
            }
        }
    }

    private fun displayNameOf(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun showLoadError(e: Exception) {
        val msg = getString(R.string.load_failed, e.message)
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(android.R.string.copy) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", msg))
            }
            .show()
    }

    // ตั้ง layoutManager + ชนิด item ตามโหมดปัจจุบัน (ตั้ง adapter ใหม่เพื่อสร้าง ViewHolder ตามโหมด)
    private fun applyLayout() {
        adapter.isGrid = isGridView
        binding.recyclerView.layoutManager =
            if (isGridView) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    // เมนูตอนกดค้าง / กดปุ่ม ⋮
    private fun showItemMenu(p: SavedPlaylist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("เปลี่ยนชื่อ", "ลบ")) { _, which ->
                if (which == 0) showRenameDialog(p) else confirmRemove(p)
            }
            .show()
    }

    private fun showRenameDialog(p: SavedPlaylist) {
        val input = EditText(this).apply {
            setText(p.name)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("เปลี่ยนชื่อ")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && n != p.name) {
                    store.rename(p.id, n)
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(p: SavedPlaylist) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_confirm, p.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                store.remove(p.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
