package com.example.mediacleaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val uris = mutableListOf<Uri>()
    private var next = 0
    private val batchSize = 500
    private val reqPerm = 1
    private val reqDel = 2

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var scanBtn: Button
    private lateinit var deleteBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "مسح كل الصور والفيديوهات"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "دوس على فحص الأول"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        scanBtn = Button(this).apply {
            text = "1) فحص الصور والفيديوهات"
            setOnClickListener { startScan() }
        }
        details = TextView(this).apply { textSize = 14f }
        val scroll = ScrollView(this).apply { addView(details) }
        deleteBtn = Button(this).apply {
            text = "2) مسح الكل"
            isEnabled = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setOnClickListener { confirmDelete() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(scanBtn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(deleteBtn)
        setContentView(root)
    }

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun startScan() {
        val perms = permissions()
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            scan()
        } else {
            requestPermissions(perms, reqPerm)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == reqPerm && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            scan()
        } else {
            Toast.makeText(this, "لازم توافق على صلاحية الصور والفيديوهات", Toast.LENGTH_LONG).show()
        }
    }

    private fun scan() {
        uris.clear()
        next = 0
        val folders = HashMap<String, LongArray>() // [count, size]
        var total = 0L
        var images = 0
        var videos = 0

        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to true,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to false
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        for ((collection, isImage) in collections) {
            contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val size = c.getLong(sizeCol)
                    val path = c.getString(pathCol) ?: "غير معروف"
                    uris.add(ContentUris.withAppendedId(collection, id))
                    val f = folders.getOrPut(path) { LongArray(2) }
                    f[0]++
                    f[1] += size
                    total += size
                    if (isImage) images++ else videos++
                }
            }
        }

        status.text = "لقيت $images صورة و $videos فيديو\nالحجم الكلي: ${Formatter.formatFileSize(this, total)}"
        details.text = folders.entries
            .sortedByDescending { it.value[1] }
            .joinToString("\n") {
                "${it.key}  ←  ${it.value[0]} ملف (${Formatter.formatFileSize(this, it.value[1])})"
            }
        deleteBtn.isEnabled = uris.isNotEmpty()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("تأكيد نهائي")
            .setMessage("هيتم مسح ${uris.size} ملف (صور وفيديوهات) من الفون نهائيا ومفيش رجوع. متأكد؟")
            .setPositiveButton("امسح الكل") { _, _ -> deleteNextBatch() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun deleteNextBatch() {
        if (next >= uris.size) {
            Toast.makeText(this, "تم المسح ✅", Toast.LENGTH_LONG).show()
            scan()
            return
        }
        val end = minOf(next + batchSize, uris.size)
        val batch = uris.subList(next, end).toList()
        val pi = MediaStore.createDeleteRequest(contentResolver, batch)
        startIntentSenderForResult(pi.intentSender, reqDel, null, 0, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqDel) {
            if (resultCode == RESULT_OK) {
                next += batchSize
                deleteNextBatch()
            } else {
                Toast.makeText(this, "اتلغى المسح", Toast.LENGTH_LONG).show()
                scan()
            }
        }
    }
}
